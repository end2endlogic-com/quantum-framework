package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.ontology.core.EdgeChanges;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write-triggered chain propagation (P2): under {@code [hasPO, hasLine, lineShipment] => hasShipment},
 * a change at a PurchaseOrder or a POLine re-derives the Customer at the head of the chain without
 * the Customer being saved again, and a removed link prunes the derived edge.
 */
@QuarkusTest
public class ChainPropagationTest {

    @Inject
    TenantOntologyTBoxRepo tboxRepo;

    @Inject
    TenantOntologyRegistryProvider provider;

    @Inject
    OntologyMaterializer materializer;

    @Inject
    ChainPropagator propagator;

    @Inject
    OntologyEdgeRepo edgeRepo;

    private DataDomain tenant;
    private SecurityCallScope.Scope ignoredRulesScope;

    @BeforeEach
    void setup() {
        ignoredRulesScope = SecurityCallScope.openIgnoringRules();
        tboxRepo.deleteAll();
        edgeRepo.deleteAll();
        provider.clearCache();
        tenant = new DataDomain("org1", "acc1", "chain-propagation-tenant", 0, "user1");
        TenantOntologyTBox doc = new TenantOntologyTBox(ordersTBox(), "chain-hash", "chain-yaml", "test", "1.0.0");
        doc.setDataDomain(tenant);
        doc.setRefName("tbox-chain-propagation");
        doc.setDisplayName("Chain propagation");
        tboxRepo.save(doc);
    }

    @AfterEach
    void cleanup() {
        provider.clearCache();
        if (ignoredRulesScope != null) {
            ignoredRulesScope.close();
        }
    }

    @Test
    void linkingALineDownstreamDerivesTheCustomersShipment() {
        write("c1", "Customer", edge("c1", "Customer", "hasPO", "po1", "PurchaseOrder"));
        write("po1", "PurchaseOrder", edge("po1", "PurchaseOrder", "hasLine", "l1", "POLine"));
        assertFalse(hasShipment("c1", "s1"));

        ChainPropagator.Result result = write("l1", "POLine", edge("l1", "POLine", "lineShipment", "s1", "Shipment"));

        assertTrue(hasShipment("c1", "s1"), "the Customer was not re-derived");
        assertEquals(1, result.rederived());
        assertFalse(result.truncated());
    }

    @Test
    void linkingTheMiddleHopDerivesTheCustomersShipment() {
        write("c1", "Customer", edge("c1", "Customer", "hasPO", "po1", "PurchaseOrder"));
        write("l1", "POLine", edge("l1", "POLine", "lineShipment", "s1", "Shipment"));

        write("po1", "PurchaseOrder", edge("po1", "PurchaseOrder", "hasLine", "l1", "POLine"));

        assertTrue(hasShipment("c1", "s1"));
    }

    @Test
    void unlinkingALinePrunesTheDerivedEdge() {
        write("c1", "Customer", edge("c1", "Customer", "hasPO", "po1", "PurchaseOrder"));
        write("po1", "PurchaseOrder", edge("po1", "PurchaseOrder", "hasLine", "l1", "POLine"));
        write("l1", "POLine", edge("l1", "POLine", "lineShipment", "s1", "Shipment"));
        assertTrue(hasShipment("c1", "s1"));

        write("l1", "POLine");

        assertFalse(hasShipment("c1", "s1"), "the stale derived edge was not pruned");
    }

    @Test
    void deletingALinePrunesTheDerivedEdge() {
        write("c1", "Customer", edge("c1", "Customer", "hasPO", "po1", "PurchaseOrder"));
        write("po1", "PurchaseOrder", edge("po1", "PurchaseOrder", "hasLine", "l1", "POLine"));
        write("l1", "POLine", edge("l1", "POLine", "lineShipment", "s1", "Shipment"));

        // What OntologyDeleteHook.afterDelete does: drop the node's edges, then propagate their predicates
        edgeRepo.deleteBySrc((String) null, tenant, "l1", false);
        propagator.propagate(null, tenant, "l1", Set.of("lineShipment"));

        assertFalse(hasShipment("c1", "s1"));
    }

    @Test
    void everyHeadUpstreamIsReDerived() {
        write("c1", "Customer", edge("c1", "Customer", "hasPO", "po1", "PurchaseOrder"));
        write("c2", "Customer", edge("c2", "Customer", "hasPO", "po2", "PurchaseOrder"));
        write("po1", "PurchaseOrder", edge("po1", "PurchaseOrder", "hasLine", "l1", "POLine"));
        write("po2", "PurchaseOrder", edge("po2", "PurchaseOrder", "hasLine", "l1", "POLine"));

        ChainPropagator.Result result = write("l1", "POLine", edge("l1", "POLine", "lineShipment", "s1", "Shipment"));

        assertTrue(hasShipment("c1", "s1"));
        assertTrue(hasShipment("c2", "s1"));
        assertEquals(2, result.rederived());
    }

    @Test
    void theBudgetStopsPropagationAndNamesWhatWasLeft() {
        write("c1", "Customer", edge("c1", "Customer", "hasPO", "po1", "PurchaseOrder"));
        write("c2", "Customer", edge("c2", "Customer", "hasPO", "po2", "PurchaseOrder"));
        write("po1", "PurchaseOrder", edge("po1", "PurchaseOrder", "hasLine", "l1", "POLine"));
        write("po2", "PurchaseOrder", edge("po2", "PurchaseOrder", "hasLine", "l1", "POLine"));
        EdgeChanges changes = materializer.apply(tenant, "l1", "POLine",
                List.of(edge("l1", "POLine", "lineShipment", "s1", "Shipment")));

        ChainPropagator bounded = new ChainPropagator(materializer, edgeRepo, provider, 1);
        ChainPropagator.Result result = bounded.propagate(null, tenant, "l1", changes);

        assertTrue(result.truncated());
        assertEquals(1, result.rederived());
        assertTrue(hasShipment("c1", "s1") ^ hasShipment("c2", "s1"), "exactly one head fits the budget");
        String missed = hasShipment("c1", "s1") ? "c2" : "c1";
        // The head that was not reached is named; the one re-derived stays queued for its own onward walk
        assertTrue(result.pendingHeads().contains(missed), result.pendingHeads().toString());
    }

    @Test
    void anEntityWithNoChainThroughItReDerivesNothing() {
        ChainPropagator.Result result = write("s1", "Shipment", edge("s1", "Shipment", "shipmentOf", "c1", "Customer"));

        assertEquals(0, result.rederived());
    }

    /** What OntologyWriteHook.afterPersist does: materialize the entity, then propagate its changes. */
    private ChainPropagator.Result write(String id, String type, Reasoner.Edge... edges) {
        EdgeChanges changes = materializer.apply(tenant, id, type, List.of(edges));
        return propagator.propagate(null, tenant, id, changes);
    }

    private boolean hasShipment(String customer, String shipment) {
        // A null realm id reads the security-context realm, where materializer.apply(DataDomain, ...) writes
        return edgeRepo.findBySrc((String) null, tenant, customer).stream()
                .anyMatch(e -> "hasShipment".equals(e.getP()) && shipment.equals(e.getDst()) && e.isInferred());
    }

    private static Reasoner.Edge edge(String src, String srcType, String p, String dst, String dstType) {
        return new Reasoner.Edge(src, srcType, p, dst, dstType, false, Optional.empty());
    }

    private static TBox ordersTBox() {
        Map<String, ClassDef> classes = new LinkedHashMap<>();
        for (String c : List.of("Customer", "PurchaseOrder", "POLine", "Shipment")) {
            classes.put(c, new ClassDef(c, Set.of(), Set.of(), Set.of()));
        }
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("hasPO", prop("hasPO", "Customer", "PurchaseOrder", false));
        props.put("hasLine", prop("hasLine", "PurchaseOrder", "POLine", false));
        props.put("lineShipment", prop("lineShipment", "POLine", "Shipment", false));
        props.put("shipmentOf", prop("shipmentOf", "Shipment", "Customer", false));
        props.put("hasShipment", prop("hasShipment", "Customer", "Shipment", true));
        return new TBox(classes, props,
                List.of(new PropertyChainDef(List.of("hasPO", "hasLine", "lineShipment"), "hasShipment")));
    }

    private static PropertyDef prop(String name, String domain, String range, boolean inferred) {
        return new PropertyDef(name, Optional.of(domain), Optional.of(range),
                false, Optional.empty(), false, false, false, Set.of(), inferred);
    }
}
