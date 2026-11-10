package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.core.RelationStability;
import com.e2eq.ontology.core.StableRelationGuard;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relation contract (P1) through Mongo: stability persists and round-trips on the tenant TBox,
 * survives the registry provider's merge, and explicit edges for a stable derived relation are
 * refused by the materializer and the edge repository.
 */
@QuarkusTest
public class StableRelationPersistenceTest {

    private static final String MESSAGE = "Explicit edge for stable derived relation 'hasShipment' is not allowed:"
            + " its edges come only from its derivation";

    @Inject
    TenantOntologyTBoxRepo tboxRepo;

    @Inject
    TenantOntologyRegistryProvider provider;

    @Inject
    OntologyMaterializer materializer;

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
        tenant = new DataDomain("org1", "acc1", "stable-relation-tenant", 0, "user1");
        TenantOntologyTBox doc = new TenantOntologyTBox(ordersTBox(), "stable-hash", "stable-yaml", "test", "1.0.0");
        doc.setDataDomain(tenant);
        doc.setRefName("tbox-stable-relations");
        doc.setDisplayName("Stable relations");
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
    void stabilityPersistsOnlyWhenStableAndRoundTrips() {
        TenantOntologyTBox stored = tboxRepo.findActiveTBox(tenant).orElseThrow();
        assertEquals("stable", stored.getProperties().get("hasShipment").getStability());
        assertNull(stored.getProperties().get("hasPO").getStability(), "internal is omitted at default");

        TBox tbox = stored.toTBox();
        assertTrue(tbox.properties().get("hasShipment").isStableDerived());
        assertEquals(RelationStability.INTERNAL, tbox.properties().get("hasPO").stability());
    }

    @Test
    void unknownStoredStabilityFailsOnRead() {
        TenantOntologyTBox stored = tboxRepo.findActiveTBox(tenant).orElseThrow();
        stored.getProperties().get("hasPO").setStability("frozen");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, stored::toTBox);
        assertEquals("Property 'hasPO' has unknown stability 'frozen': use stable or internal", ex.getMessage());
    }

    @Test
    void registryProviderKeepsStability() {
        OntologyRegistry registry = provider.getRegistryForTenant(tenant);
        assertTrue(registry.propertyOf("hasShipment").orElseThrow().isStable());
        assertFalse(registry.propertyOf("hasPO").orElseThrow().isStable());
    }

    @Test
    void materializerRefusesExplicitEdgesForStableDerivedRelations() {
        Reasoner.Edge explicit = new Reasoner.Edge("c-refused", "Customer", "hasShipment", "s1", "Shipment", false, Optional.empty());
        Reasoner.Edge allowed = new Reasoner.Edge("c-refused", "Customer", "hasPO", "po1", "PurchaseOrder", false, Optional.empty());
        StableRelationGuard.StableDerivedEdgeException ex = assertThrows(StableRelationGuard.StableDerivedEdgeException.class,
                () -> materializer.apply(tenant, "c-refused", "Customer", List.of(allowed, explicit)));
        assertEquals(MESSAGE, ex.getMessage());
        assertTrue(edgeRepo.findBySrc(tenant, "c-refused").isEmpty(), "nothing is written when the edges are refused");
    }

    @Test
    void materializerStillDerivesTheStableRelationFromItsChain() {
        materializer.apply(tenant, "po1", "PurchaseOrder",
                List.of(new Reasoner.Edge("po1", "PurchaseOrder", "poShipment", "s1", "Shipment", false, Optional.empty())));
        materializer.apply(tenant, "c1", "Customer",
                List.of(new Reasoner.Edge("c1", "Customer", "hasPO", "po1", "PurchaseOrder", false, Optional.empty())));
        assertTrue(edgeRepo.findBySrc(tenant, "c1").stream()
                .anyMatch(e -> "hasShipment".equals(e.getP()) && "s1".equals(e.getDst()) && e.isInferred()));
    }

    @Test
    void edgeRepoRefusesExplicitWritesButNotInferredOnes() {
        StableRelationGuard.StableDerivedEdgeException ex = assertThrows(StableRelationGuard.StableDerivedEdgeException.class,
                () -> edgeRepo.upsert(tenant, "Customer", "c1", "hasShipment", "Shipment", "s1", false, Map.of()));
        assertEquals(MESSAGE, ex.getMessage());
        edgeRepo.upsert(tenant, "Customer", "c1", "hasShipment", "Shipment", "s1", true, Map.of());
        edgeRepo.upsert(tenant, "Customer", "c1", "hasPO", "PurchaseOrder", "po1", false, Map.of());
        assertEquals(2, edgeRepo.findBySrc(tenant, "c1").size());
    }

    private static TBox ordersTBox() {
        Map<String, ClassDef> classes = new LinkedHashMap<>();
        for (String c : List.of("Customer", "PurchaseOrder", "Shipment")) {
            classes.put(c, new ClassDef(c, Set.of(), Set.of(), Set.of()));
        }
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("hasPO", new PropertyDef("hasPO", Optional.of("Customer"), Optional.of("PurchaseOrder"),
                false, Optional.empty(), false, false, false, Set.of()));
        props.put("poShipment", new PropertyDef("poShipment", Optional.of("PurchaseOrder"), Optional.of("Shipment"),
                false, Optional.empty(), false, false, false, Set.of()));
        props.put("hasShipment", new PropertyDef("hasShipment", Optional.of("Customer"), Optional.of("Shipment"),
                false, Optional.empty(), false, false, false, Set.of(), true, null, Set.of(), Map.of(),
                RelationStability.STABLE));
        return new TBox(classes, props, List.of(new PropertyChainDef(List.of("hasPO", "poShipment"), "hasShipment")));
    }
}
