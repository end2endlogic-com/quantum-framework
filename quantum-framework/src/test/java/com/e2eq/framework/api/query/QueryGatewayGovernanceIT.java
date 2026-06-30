package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security regression tests for {@link QueryGatewayResource}.
 *
 * <p>These prove the gateway no longer bypasses row-level and field-level governance: the generic
 * find/count/delete/deleteMany paths now AND the caller's query with the RuleContext-derived
 * DataDomain scope (and strip excluded fields), so a principal can only see and mutate rows inside
 * its own DataDomain.</p>
 *
 * <p>Two tenants are exercised: tenant A is the standard test tenant (principal has the {@code admin}
 * role, whose default rule filters {@code dataDomain.tenantId:${pTenantId}}); tenant B is a synthetic
 * tenant whose rows must never be visible/mutable to the tenant-A principal.</p>
 *
 * <p>Requires Mongo on :27017 (QuarkusTest harness).</p>
 */
@QuarkusTest
public class QueryGatewayGovernanceIT {

    @Inject
    QueryGatewayResource resource;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    TestUtils testUtils;

    @Inject
    RuleContext ruleContext;

    private String realm;
    private String marker;

    private DataDomain ddA;
    private DataDomain ddB;
    private PrincipalContext pcA;
    private ResourceContext rc;

    @BeforeEach
    public void setUp() {
        ruleContext.ensureDefaultRules();
        realm = testUtils.getTestRealm();
        marker = "gov-" + System.currentTimeMillis();
        morphiaDataStoreWrapper.getDataStore(realm);

        DataDomain base = testUtils.getTestPrincipalContext(testUtils.getTestUserId(), new String[]{"admin"}).getDataDomain();
        ddA = new DataDomain(base.getOrgRefName(), base.getAccountNum(), base.getTenantId(), base.getDataSegment(), base.getOwnerId());
        ddB = new DataDomain(base.getOrgRefName(), base.getAccountNum(), base.getTenantId() + "-OTHER", base.getDataSegment(), "ownerB");

        pcA = testUtils.getTestPrincipalContext(testUtils.getTestUserId(), new String[]{"admin", "user"});
        rc = testUtils.getResourceContext("integration", "query", "find");

        seedRow(ddA, marker, "a1");
        seedRow(ddA, marker, "a2");
        seedRow(ddB, marker, "b1");
    }

    private CodeList seedRow(DataDomain dd, String category, String key) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        CodeList cl = new CodeList();
        cl.setCategory(category);
        cl.setKey(key);
        cl.setRefName(category + ":" + key);
        cl.setDescription("secret-" + key);
        cl.setValueType("STRING");
        cl.setDataDomain(dd);
        return ds.save(cl);
    }

    @SuppressWarnings("unchecked")
    private List<CodeList> rowsOf(Response r) {
        Object entity = r.getEntity();
        assertNotNull(entity, "find response entity should not be null");
        try {
            var m = entity.getClass().getMethod("getRows");
            return (List<CodeList>) m.invoke(entity);
        } catch (Exception e) {
            throw new RuntimeException("Unable to read rows from response envelope", e);
        }
    }

    // ------------------------------------------------------------------
    // (a) find/count return ONLY rows in the caller's DataDomain
    // ------------------------------------------------------------------

    @Test
    public void find_returns_only_callers_dataDomain_rows() {
        try (SecuritySession ignored = new SecuritySession(pcA, rc)) {
            QueryGatewayResource.FindRequest req = new QueryGatewayResource.FindRequest();
            req.rootType = CodeList.class.getName();
            req.query = "category:" + marker;
            req.realm = realm;

            Response resp = resource.find(req);
            assertEquals(200, resp.getStatus());
            List<CodeList> rows = rowsOf(resp);

            assertEquals(2, rows.size(), "find must return only the 2 tenant-A rows, not the tenant-B row");
            for (CodeList row : rows) {
                assertNotNull(row.getDataDomain());
                assertEquals(ddA.getTenantId(), row.getDataDomain().getTenantId(),
                        "no cross-tenant row may appear in results");
            }
        }
    }

    @Test
    public void count_counts_only_callers_dataDomain_rows() {
        try (SecuritySession ignored = new SecuritySession(pcA, rc)) {
            QueryGatewayResource.CountRequest req = new QueryGatewayResource.CountRequest();
            req.rootType = CodeList.class.getName();
            req.query = "category:" + marker;
            req.realm = realm;

            Response resp = resource.count(req);
            assertEquals(200, resp.getStatus());
            QueryGatewayResource.CountResponse body = (QueryGatewayResource.CountResponse) resp.getEntity();
            assertEquals(2L, body.count, "count must reflect only the 2 tenant-A rows");
        }
    }

    // ------------------------------------------------------------------
    // (b) excluded fields are stripped from results
    // ------------------------------------------------------------------

    @Test
    public void find_strips_excluded_fields() {
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity("admin").withArea("*").withFunctionalDomain("*").withAction("*").build();
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withOrgRefName("*").withAccountNumber("*").withRealm("*")
                .withTenantId("*").withOwnerId("*").withDataSegment("*").build();
        Rule excludeRule = new Rule.Builder()
                .withName("gateway-test-exclude-description-" + marker)
                .withSecurityURI(new SecurityURI(header, body))
                .withEffect(RuleEffect.ALLOW)
                .withAndFilterString("dataDomain.tenantId:${pTenantId}")
                .withExcludedFields(List.of("description"))
                .withPriority(-50)
                .withFinalRule(false)
                .build();
        ruleContext.addRule(header, excludeRule);
        // Invalidate the realm's compiled rule index so the newly added rule (with its
        // excludedFields) is recompiled into the effective rule set for this realm.
        ruleContext.clearCacheForRealm(realm);
        RuleContext.clearRequestCache();

        try (SecuritySession ignored = new SecuritySession(pcA, rc)) {
            // Precondition: the rule's excludedFields must resolve for this principal/resource,
            // otherwise the test is asserting on rule-injection plumbing rather than the gateway.
            java.util.Set<String> excluded = ruleContext.getExcludedFieldPaths(pcA, rc);
            assertTrue(excluded.contains("description"),
                    "expected RuleContext to resolve excluded field 'description'; got " + excluded);

            QueryGatewayResource.FindRequest req = new QueryGatewayResource.FindRequest();
            req.rootType = CodeList.class.getName();
            req.query = "category:" + marker;
            req.realm = realm;

            Response resp = resource.find(req);
            assertEquals(200, resp.getStatus());
            List<CodeList> rows = rowsOf(resp);
            assertFalse(rows.isEmpty(), "should still return the in-domain rows");
            for (CodeList row : rows) {
                assertNull(row.getDescription(),
                        "excluded field 'description' must be stripped from gateway results");
            }
        } finally {
            ruleContext.clear();
            ruleContext.ensureDefaultRules();
        }
    }

    // ------------------------------------------------------------------
    // (c) delete / deleteMany cannot affect rows outside the caller's DataDomain
    // ------------------------------------------------------------------

    @Test
    public void delete_by_id_cannot_remove_cross_dataDomain_row() {
        CodeList bRow = seedRow(ddB, marker + "-del", "bDel");
        String bId = bRow.getId().toHexString();

        try (SecuritySession ignored = new SecuritySession(pcA, rc)) {
            QueryGatewayResource.DeleteRequest req = new QueryGatewayResource.DeleteRequest();
            req.rootType = CodeList.class.getName();
            req.realm = realm;
            req.id = bId;

            Response resp = resource.delete(req);
            assertEquals(404, resp.getStatus(), "tenant-A principal must not be able to delete a tenant-B row");
        }

        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        CodeList still = ds.find(CodeList.class).filter(Filters.eq("_id", bRow.getId())).first();
        assertNotNull(still, "tenant-B row must NOT have been deleted by the tenant-A principal");
    }

    @Test
    public void deleteMany_cannot_remove_cross_dataDomain_rows() {
        String delMarker = marker + "-dm";
        seedRow(ddA, delMarker, "a1");
        CodeList b1 = seedRow(ddB, delMarker, "b1");

        try (SecuritySession ignored = new SecuritySession(pcA, rc)) {
            QueryGatewayResource.DeleteManyRequest req = new QueryGatewayResource.DeleteManyRequest();
            req.rootType = CodeList.class.getName();
            req.realm = realm;
            req.query = "category:" + delMarker;

            Response resp = resource.deleteMany(req);
            assertEquals(200, resp.getStatus());
            QueryGatewayResource.DeleteManyResponse body = (QueryGatewayResource.DeleteManyResponse) resp.getEntity();
            assertEquals(1L, body.deletedCount,
                    "deleteMany must only delete the single tenant-A row, never the tenant-B row");
        }

        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        CodeList stillB = ds.find(CodeList.class).filter(Filters.eq("_id", b1.getId())).first();
        assertNotNull(stillB, "tenant-B row must survive a tenant-A deleteMany");
    }
}
