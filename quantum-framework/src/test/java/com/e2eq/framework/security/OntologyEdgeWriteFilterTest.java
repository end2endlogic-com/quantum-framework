package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.persistent.EdgeGuardCustomerRepo;
import com.e2eq.framework.persistent.EdgeGuardOrderRepo;
import com.e2eq.framework.test.EdgeGuardCustomer;
import com.e2eq.framework.test.EdgeGuardOrder;
import com.e2eq.framework.tests.securityrules.TestEdgeGuardResolver;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write-side rule filters and the ontology edges a save materializes.
 *
 * <p>A caller's write rule filters its own records with a resolver variable that is resolved for
 * its business type only ({@code refName:^[${edgeGuardRefNames}]}, as an application partner
 * portal does). Saving a record the caller may write materializes ontology edges through the write
 * hook. Those edges derive from an already-authorized write and are not judged by the caller's row
 * filter, which does not apply to OntologyEdge and would fail closed. A direct edge write by the
 * same caller is still checked.</p>
 */
@QuarkusTest
public class OntologyEdgeWriteFilterTest extends BaseRepoTest {

    private static final String ROLE = "edge-guard-partner";
    private static final String AREA = "EDGEGUARD";
    private static final String DOMAIN = "ORDER";

    @Inject
    EdgeGuardOrderRepo orderRepo;

    @Inject
    EdgeGuardCustomerRepo customerRepo;

    @Inject
    OntologyEdgeRepo edgeRepo;

    private EdgeGuardCustomer customer;

    @BeforeEach
    void setUp() {
        SecurityContext.clear();
        cleanUp();
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system(), resource("save"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            EdgeGuardCustomer c = new EdgeGuardCustomer();
            c.setRefName("edge-guard-customer");
            c.setDataDomain(testUtils.getTestDataDomain());
            customer = customerRepo.save(testUtils.getTestRealm(), c);
        }
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity(ROLE).withArea(AREA).withFunctionalDomain(DOMAIN).withAction("*").build();
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withRealm("*").withOrgRefName("*").withAccountNumber("*").withTenantId("*")
                .withOwnerId("*").withDataSegment("*").withResourceId("*").build();
        ruleContext.addRule(header, new Rule.Builder()
                .withName("edge-guard-partner-own-orders")
                .withSecurityURI(new SecurityURI(header, body))
                .withAndFilterString("refName:^[${" + TestEdgeGuardResolver.KEY + "}]")
                .withEffect(RuleEffect.ALLOW)
                .withPriority(10)
                .withFinalRule(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
        cleanUp();
        SecurityContext.clear();
    }

    @Test
    public void savingAnAllowedRecordMaterializesItsEdges() {
        EdgeGuardOrder saved;
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(partner(), resource("save"))) {
            saved = orderRepo.save(testUtils.getTestRealm(), order("edge-guard-order-ok"));
        }
        assertNotNull(saved.getId());

        List<OntologyEdge> edges = edgesFrom(saved.getId().toString());
        assertTrue(edges.stream().anyMatch(e -> "placedBy".equals(e.getP())
                        && customer.getId().toString().equals(e.getDst())),
                "the save's placedBy edge must be materialized, not refused by the caller's row filter: " + edges);
    }

    @Test
    public void savingARecordOutsideTheFilterIsStillDenied() {
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(partner(), resource("save"))) {
            assertThrows(SecurityCheckException.class,
                    () -> orderRepo.save(testUtils.getTestRealm(), order("edge-guard-order-other")));
        }
    }

    @Test
    public void directEdgeWriteByTheCallerIsStillChecked() {
        DataDomain domain = testUtils.getTestDataDomain();
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(partner(), resource("save"))) {
            assertThrows(SecurityCheckException.class, () -> edgeRepo.upsert(testUtils.getTestRealm(), domain,
                    "EdgeGuardOrder", "edge-guard-forged", "placedBy", "EdgeGuardCustomer",
                    customer.getId().toString(), false, Map.of()));
        }
        assertTrue(edgesFrom("edge-guard-forged").isEmpty(), "the refused edge must not have been written");
    }

    // ---------------------------------------------------------------------------- helpers

    private EdgeGuardOrder order(String refName) {
        EdgeGuardOrder o = new EdgeGuardOrder();
        o.setRefName(refName);
        o.setPlacedBy(customer);
        return o;
    }

    private List<OntologyEdge> edgesFrom(String src) {
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system(), resource("view"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            return edgeRepo.findBySrc(testUtils.getTestRealm(), testUtils.getTestDataDomain(), src);
        }
    }

    private void cleanUp() {
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system(), resource("delete"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            String realm = testUtils.getTestRealm();
            edgeRepo.ds(realm).getCollection(OntologyEdge.class).deleteMany(
                    new org.bson.Document("srcType", "EdgeGuardOrder"));
            orderRepo.getAllList(realm).forEach(o -> deleteQuietly(() -> orderRepo.delete(realm, o)));
            customerRepo.getAllList(realm).forEach(c -> deleteQuietly(() -> customerRepo.delete(realm, c)));
        }
    }

    private interface Deletion {
        void run() throws Exception;
    }

    private static void deleteQuietly(Deletion deletion) {
        try {
            deletion.run();
        } catch (Exception e) {
            throw new IllegalStateException("cleanup failed", e);
        }
    }

    private PrincipalContext partner() {
        return testUtils.getTestPrincipalContext(ROLE + "@test.local", new String[]{ROLE});
    }

    private PrincipalContext system() {
        return testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
    }

    private ResourceContext resource(String action) {
        return new ResourceContext.Builder().withArea(AREA).withFunctionalDomain(DOMAIN).withAction(action).build();
    }
}
