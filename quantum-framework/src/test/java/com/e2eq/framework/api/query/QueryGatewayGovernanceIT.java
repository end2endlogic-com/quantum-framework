package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.ProjectionField;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.CodeListRepo;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashMap;

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

    @Inject
    CodeListRepo codeListRepo;

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
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            return ds.save(cl);
        }
    }

    private void installDescriptionExclusionRule() {
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
        ruleContext.clearCacheForRealm(realm);
        RuleContext.clearRequestCache();
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
        installDescriptionExclusionRule();

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

    @Test
    public void repository_point_reads_apply_excluded_field_projection() {
        CodeList stored = seedRow(ddA, marker + "-point", "point");
        installDescriptionExclusionRule();

        try (SecuritySession ignored = new SecuritySession(pcA, rc)) {
            CodeList byId = codeListRepo.findById(stored.getId(), realm).orElseThrow();
            assertNull(byId.getDescription(), "findById must apply field policy projection");

            CodeList byRefName = codeListRepo.findByRefName(stored.getRefName(), realm).orElseThrow();
            assertNull(byRefName.getDescription(), "findByRefName must apply field policy projection");

            List<CodeList> byIds = codeListRepo.getListFromIds(realm, List.of(stored.getId()));
            assertEquals(1, byIds.size());
            assertNull(byIds.get(0).getDescription(), "getListFromIds must apply field policy projection");

            List<CodeList> byRefNames = codeListRepo.getListFromRefNames(realm, List.of(stored.getRefName()));
            assertEquals(1, byRefNames.size());
            assertNull(byRefNames.get(0).getDescription(),
                    "getListFromRefNames must apply field policy projection");

            List<CodeList> protectedOnlyProjection = codeListRepo.getListByQuery(
                    realm, 0, 10, "category:" + stored.getCategory(), null,
                    List.of(new ProjectionField("description", ProjectionField.ProjectionType.INCLUDE)));
            assertEquals(1, protectedOnlyProjection.size());
            assertNotNull(protectedOnlyProjection.get(0).getId());
            assertNull(protectedOnlyProjection.get(0).getDescription(),
                    "removing the caller's only included field must not widen to a full document");
            assertNotEquals(stored.getRefName(), protectedOnlyProjection.get(0).getRefName(),
                    "an emptied include projection must not disclose the stored refName");
        } finally {
            ruleContext.clear();
            ruleContext.ensureDefaultRules();
        }
    }

    // ------------------------------------------------------------------
    // (b) save uses the registered repository and cannot choose another DataDomain
    // ------------------------------------------------------------------

    @Test
    public void save_create_stamps_callers_dataDomain_instead_of_request_body_domain() {
        QueryGatewayResource.SaveRequest req = new QueryGatewayResource.SaveRequest();
        req.rootType = CodeList.class.getName();
        req.realm = realm;
        req.entity = new HashMap<>();
        req.entity.put("category", marker + "-save");
        req.entity.put("key", "created");
        req.entity.put("refName", marker + "-save:created");
        req.entity.put("valueType", "STRING");
        req.entity.put("dataDomain", ddB);

        String id;
        ResourceContext saveContext = testUtils.getResourceContext("integration", "query", "save");
        try (SecuritySession ignored = new SecuritySession(pcA, saveContext)) {
            Response response = resource.save(req);
            assertEquals(200, response.getStatus());
            QueryGatewayResource.SaveResponse body = (QueryGatewayResource.SaveResponse) response.getEntity();
            id = body.id;
        }

        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        CodeList stored = ds.find(CodeList.class)
                .filter(Filters.eq("_id", new org.bson.types.ObjectId(id)))
                .first();
        assertNotNull(stored);
        assertEquals(ddA.getTenantId(), stored.getDataDomain().getTenantId(),
                "generic save must stamp the authenticated caller's DataDomain");
        ds.find(CodeList.class).filter(Filters.eq("_id", stored.getId())).delete();
    }

    @Test
    public void save_update_preserves_excluded_field_and_masks_save_response() {
        CodeList stored = seedRow(ddA, marker + "-save-field", "protected");
        installDescriptionExclusionRule();

        QueryGatewayResource.SaveRequest req = new QueryGatewayResource.SaveRequest();
        req.rootType = CodeList.class.getName();
        req.realm = realm;
        req.entity = new HashMap<>();
        req.entity.put("id", stored.getId());
        req.entity.put("category", stored.getCategory());
        req.entity.put("key", stored.getKey());
        req.entity.put("refName", stored.getRefName());
        req.entity.put("version", stored.getVersion());
        req.entity.put("description", "overwrite-attempt");
        req.entity.put("valueType", "STRING");
        req.entity.put("dataDomain", ddA);

        ResourceContext saveContext = testUtils.getResourceContext("integration", "query", "save");
        try (SecuritySession ignored = new SecuritySession(pcA, saveContext)) {
            Response response = resource.save(req);
            assertEquals(200, response.getStatus());
            QueryGatewayResource.SaveResponse body = (QueryGatewayResource.SaveResponse) response.getEntity();
            assertFalse(body.entity.containsKey("description"),
                    "save response must not disclose a field excluded by policy");
        } finally {
            ruleContext.clear();
            ruleContext.ensureDefaultRules();
        }

        CodeList persisted = morphiaDataStoreWrapper.getDataStore(realm).find(CodeList.class)
                .filter(Filters.eq("_id", stored.getId())).first();
        assertNotNull(persisted);
        assertEquals("secret-protected", persisted.getDescription(),
                "standard repository save must preserve the stored protected value");
    }

    @Test
    public void save_create_rejects_policy_excluded_field() {
        installDescriptionExclusionRule();
        String refName = marker + "-create-field:protected";

        QueryGatewayResource.SaveRequest req = new QueryGatewayResource.SaveRequest();
        req.rootType = CodeList.class.getName();
        req.realm = realm;
        req.entity = new HashMap<>();
        req.entity.put("category", marker + "-create-field");
        req.entity.put("key", "protected");
        req.entity.put("refName", refName);
        req.entity.put("description", "create-attempt");
        req.entity.put("valueType", "STRING");

        ResourceContext saveContext = testUtils.getResourceContext("integration", "query", "save");
        try (SecuritySession ignored = new SecuritySession(pcA, saveContext)) {
            ForbiddenException denied = assertThrows(ForbiddenException.class, () -> resource.save(req));
            assertTrue(denied.getMessage().contains("protected by field-level policy"));
        } finally {
            ruleContext.clear();
            ruleContext.ensureDefaultRules();
        }

        CodeList persisted = morphiaDataStoreWrapper.getDataStore(realm).find(CodeList.class)
                .filter(Filters.eq("refName", refName)).first();
        assertNull(persisted, "a create containing a protected field must not be persisted");
    }

    @Test
    public void direct_field_update_rejects_policy_excluded_path() {
        CodeList stored = seedRow(ddA, marker + "-direct-field-update", "protected-update");
        installDescriptionExclusionRule();

        ResourceContext updateContext = testUtils.getResourceContext("integration", "query", "save");
        try (SecuritySession ignored = new SecuritySession(pcA, updateContext)) {
            MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            assertThrows(SecurityException.class, () -> codeListRepo.update(
                    ds, stored.getId(), Pair.of("description", "overwrite-attempt")));
        } finally {
            ruleContext.clear();
            ruleContext.ensureDefaultRules();
        }

        CodeList persisted = morphiaDataStoreWrapper.getDataStore(realm).find(CodeList.class)
                .filter(Filters.eq("_id", stored.getId())).first();
        assertNotNull(persisted);
        assertEquals("secret-protected-update", persisted.getDescription());
    }

    @Test
    public void save_update_cannot_upsert_or_overwrite_cross_dataDomain_row() {
        CodeList bRow = seedRow(ddB, marker + "-save-cross", "bSave");

        QueryGatewayResource.SaveRequest req = new QueryGatewayResource.SaveRequest();
        req.rootType = CodeList.class.getName();
        req.realm = realm;
        req.entity = new HashMap<>();
        req.entity.put("id", bRow.getId());
        req.entity.put("category", bRow.getCategory());
        req.entity.put("key", bRow.getKey());
        req.entity.put("refName", bRow.getRefName());
        req.entity.put("description", "tenant-A-overwrite-attempt");
        req.entity.put("valueType", "STRING");
        req.entity.put("dataDomain", ddA);

        ResourceContext saveContext = testUtils.getResourceContext("integration", "query", "save");
        try (SecuritySession ignored = new SecuritySession(pcA, saveContext)) {
            assertThrows(jakarta.ws.rs.NotFoundException.class, () -> resource.save(req));
        }

        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        CodeList stored = ds.find(CodeList.class).filter(Filters.eq("_id", bRow.getId())).first();
        assertNotNull(stored);
        assertEquals("secret-bSave", stored.getDescription(),
                "a cross-domain update must leave the stored row unchanged");
    }

    @Test
    public void save_rejects_body_realm_that_differs_from_authenticated_realm() {
        QueryGatewayResource.SaveRequest req = new QueryGatewayResource.SaveRequest();
        req.rootType = CodeList.class.getName();
        req.realm = realm + "-OTHER";
        req.entity = new HashMap<>();
        req.entity.put("category", marker + "-realm");
        req.entity.put("key", "wrong-realm");
        req.entity.put("refName", marker + "-realm:wrong-realm");
        req.entity.put("valueType", "STRING");

        ResourceContext saveContext = testUtils.getResourceContext("integration", "query", "save");
        try (SecuritySession ignored = new SecuritySession(pcA, saveContext)) {
            assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> resource.save(req));
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
