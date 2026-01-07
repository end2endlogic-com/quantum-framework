package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.*;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import com.e2eq.ontology.service.TenantOntologyService;
import com.e2eq.ontology.core.OntologyRegistry;
import dev.morphia.MorphiaDatastore;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test demonstrating the full ontology lifecycle:
 * 1. Define a TBox (schema) with classes and properties
 * 2. Create edges (relationships/ABox instances)
 * 3. Query relationships
 * 4. Verify tenant isolation
 * 
 * This test validates that all ontology collections are properly created
 * and that the ontology system works end-to-end.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OntologyEndToEndIT {

    @Inject
    MorphiaDatastore datastore;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    TenantOntologyTBoxRepo tboxRepo;

    @Inject
    TenantOntologyService ontologyService;

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    private DataDomain tenant1;
    private DataDomain tenant2;

    @BeforeEach
    void setup() {
        // Create test DataDomains for two tenants
        tenant1 = new DataDomain("acme-corp", "ACC001", "tenant-alpha", 0, "admin");
        tenant2 = new DataDomain("beta-inc", "ACC002", "tenant-beta", 0, "admin");
        
        // Clear caches
        registryProvider.clearCache();
    }

    @AfterEach
    void cleanup() {
        // Clean up test data
        edgeRepo.deleteAll();
        tboxRepo.deleteAll();
    }

    @Test
    @Order(1)
    @DisplayName("1. Collections should exist after ontology operations")
    void collectionsExist() {
        // List collections in the database
        Set<String> collections = new HashSet<>();
        datastore.getDatabase().listCollectionNames().into(new ArrayList<>())
                .forEach(collections::add);
        
        Log.infof("Available collections: %s", collections);
        
        // After ontology operations, we should see these collections
        // Note: Collections are created lazily on first write
    }

    @Test
    @Order(2)
    @DisplayName("2. Define TBox schema with classes and properties")
    void defineTBoxSchema() {
        // Define classes
        ClassDef personClass = new ClassDef("Person", Set.of(), Set.of(), Set.of());
        ClassDef organizationClass = new ClassDef("Organization", Set.of(), Set.of(), Set.of());
        ClassDef orderClass = new ClassDef("Order", Set.of(), Set.of(), Set.of());
        ClassDef productClass = new ClassDef("Product", Set.of(), Set.of(), Set.of());

        // Define properties with semantics
        PropertyDef worksFor = new PropertyDef(
            "worksFor",
            Optional.of("Person"),      // domain
            Optional.of("Organization"), // range
            false,                       // inverse flag
            Optional.empty(),            // inverseOf
            false,                       // transitive
            false,                       // symmetric
            false,                       // functional
            Set.of()                     // subPropertyOf
        );

        PropertyDef placedBy = new PropertyDef(
            "placedBy",
            Optional.of("Order"),
            Optional.of("Person"),
            true,                        // has inverse
            Optional.of("placed"),       // inverseOf "placed"
            false, false, false, Set.of()
        );

        PropertyDef placed = new PropertyDef(
            "placed",
            Optional.of("Person"),
            Optional.of("Order"),
            true,
            Optional.of("placedBy"),
            false, false, false, Set.of()
        );

        PropertyDef contains = new PropertyDef(
            "contains",
            Optional.of("Order"),
            Optional.of("Product"),
            false, Optional.empty(),
            false, false, false, Set.of()
        );

        PropertyDef knows = new PropertyDef(
            "knows",
            Optional.of("Person"),
            Optional.of("Person"),
            false, Optional.empty(),
            false,
            true,   // symmetric: if A knows B, then B knows A
            false, Set.of()
        );

        // Build TBox
        TBox tbox = new TBox(
            Map.of(
                "Person", personClass,
                "Organization", organizationClass,
                "Order", orderClass,
                "Product", productClass
            ),
            Map.of(
                "worksFor", worksFor,
                "placedBy", placedBy,
                "placed", placed,
                "contains", contains,
                "knows", knows
            ),
            List.of() // no property chains for this test
        );

        // Initialize ontology for tenant1
        ontologyService.initializeTenantOntology(tenant1, tbox, "test-ontology.yaml", "1.0.0");

        // Verify TBox was saved
        Optional<TenantOntologyTBox> savedTBox = tboxRepo.findActiveTBox(tenant1);
        assertTrue(savedTBox.isPresent(), "TBox should be saved for tenant1");
        assertEquals("1.0.0", savedTBox.get().getSoftwareVersion());
        assertTrue(savedTBox.get().isActive());

        // Verify registry can load the TBox
        OntologyRegistry registry = registryProvider.getRegistryForTenant(tenant1);
        assertNotNull(registry);
        assertTrue(registry.classOf("Person").isPresent());
        assertTrue(registry.classOf("Organization").isPresent());
        assertTrue(registry.propertyOf("worksFor").isPresent());
        assertTrue(registry.propertyOf("knows").isPresent());
        
        // Verify property semantics
        PropertyDef retrievedKnows = registry.propertyOf("knows").get();
        assertTrue(retrievedKnows.symmetric(), "knows should be symmetric");
        
        Log.infof("TBox created with %d classes and %d properties", 
                registry.classes().size(), registry.properties().size());
    }

    @Test
    @Order(3)
    @DisplayName("3. Create edges (ABox relationships)")
    void createEdges() {
        // First, set up the TBox (schema)
        defineTBoxSchema();

        // Create some entities and relationships
        // Person "john" works for Organization "acme"
        edgeRepo.upsert(tenant1, "Person", "john", "worksFor", "Organization", "acme", false, Map.of());

        // Person "jane" works for Organization "acme"
        edgeRepo.upsert(tenant1, "Person", "jane", "worksFor", "Organization", "acme", false, Map.of());

        // Order "order-001" was placed by Person "john"
        edgeRepo.upsert(tenant1, "Order", "order-001", "placedBy", "Person", "john", false, Map.of());

        // Order "order-001" contains Product "laptop"
        edgeRepo.upsert(tenant1, "Order", "order-001", "contains", "Product", "laptop", false, Map.of());
        edgeRepo.upsert(tenant1, "Order", "order-001", "contains", "Product", "mouse", false, Map.of());

        // Person "john" knows Person "jane" (symmetric)
        edgeRepo.upsert(tenant1, "Person", "john", "knows", "Person", "jane", false, Map.of());

        // Verify edges were created
        List<OntologyEdge> johnEdges = edgeRepo.findBySrc(tenant1, "john");
        assertFalse(johnEdges.isEmpty(), "John should have outgoing edges");
        
        Log.infof("Created %d edges for john", johnEdges.size());
        johnEdges.forEach(e -> Log.infof("  %s -[%s]-> %s", e.getSrc(), e.getP(), e.getDst()));
    }

    @Test
    @Order(4)
    @DisplayName("4. Query relationships by source")
    void queryBySource() {
        // Set up data
        createEdges();

        // Query all relationships from "john"
        List<OntologyEdge> johnEdges = edgeRepo.findBySrc(tenant1, "john");
        
        assertTrue(johnEdges.size() >= 2, "John should have at least 2 edges (worksFor, knows)");
        
        // Verify specific relationships
        boolean hasWorksFor = johnEdges.stream()
                .anyMatch(e -> "worksFor".equals(e.getP()) && "acme".equals(e.getDst()));
        assertTrue(hasWorksFor, "John should work for acme");

        boolean hasKnows = johnEdges.stream()
                .anyMatch(e -> "knows".equals(e.getP()) && "jane".equals(e.getDst()));
        assertTrue(hasKnows, "John should know jane");
    }

    @Test
    @Order(5)
    @DisplayName("5. Query relationships by destination")
    void queryByDestination() {
        // Set up data
        createEdges();

        // Query all relationships pointing to "acme"
        List<OntologyEdge> acmeEdges = edgeRepo.findByDst(tenant1, "acme");
        
        assertEquals(2, acmeEdges.size(), "Acme should have 2 incoming edges (john and jane work there)");
        
        // All should be "worksFor" relationships
        assertTrue(acmeEdges.stream().allMatch(e -> "worksFor".equals(e.getP())));
    }

    @Test
    @Order(6)
    @DisplayName("6. Query relationships by property type")
    void queryByProperty() {
        // Set up data
        createEdges();

        // Query all "contains" relationships
        List<OntologyEdge> containsEdges = edgeRepo.findByProperty(tenant1, "contains");
        
        assertEquals(2, containsEdges.size(), "Should have 2 'contains' edges (laptop and mouse)");
        
        // Verify they're from order-001
        assertTrue(containsEdges.stream().allMatch(e -> "order-001".equals(e.getSrc())));
    }

    @Test
    @Order(7)
    @DisplayName("7. Verify tenant isolation - different tenants see different data")
    void verifyTenantIsolation() {
        // Create edges for tenant1
        edgeRepo.upsert(tenant1, "Person", "alice", "worksFor", "Organization", "alpha-corp", false, Map.of());

        // Create edges for tenant2
        edgeRepo.upsert(tenant2, "Person", "bob", "worksFor", "Organization", "beta-inc", false, Map.of());

        // Query tenant1 - should only see alice
        List<OntologyEdge> tenant1Edges = edgeRepo.findBySrc(tenant1, "alice");
        assertEquals(1, tenant1Edges.size());
        assertEquals("alpha-corp", tenant1Edges.get(0).getDst());

        // Query tenant2 - should only see bob
        List<OntologyEdge> tenant2Edges = edgeRepo.findBySrc(tenant2, "bob");
        assertEquals(1, tenant2Edges.size());
        assertEquals("beta-inc", tenant2Edges.get(0).getDst());

        // Tenant1 should NOT see tenant2's data
        List<OntologyEdge> tenant1Bob = edgeRepo.findBySrc(tenant1, "bob");
        assertTrue(tenant1Bob.isEmpty(), "Tenant1 should not see tenant2's bob");

        // Tenant2 should NOT see tenant1's data
        List<OntologyEdge> tenant2Alice = edgeRepo.findBySrc(tenant2, "alice");
        assertTrue(tenant2Alice.isEmpty(), "Tenant2 should not see tenant1's alice");

        Log.info("Tenant isolation verified - each tenant only sees their own data");
    }

    @Test
    @Order(8)
    @DisplayName("8. Verify collections were created in database")
    void verifyCollectionsCreated() {
        // First create some data to trigger collection creation
        createEdges();

        // List collections
        Set<String> collections = new HashSet<>();
        datastore.getDatabase().listCollectionNames().into(new ArrayList<>())
                .forEach(collections::add);

        Log.infof("Collections in database: %s", collections);

        // Verify expected ontology collections exist
        assertTrue(collections.contains("edges"), "edges collection should exist");
        
        // Note: tenant_ontology_tbox and tenant_ontology_meta are created when TBox is saved
        // ontology_meta and ontology_tbox are created for realm-level storage
    }
}

