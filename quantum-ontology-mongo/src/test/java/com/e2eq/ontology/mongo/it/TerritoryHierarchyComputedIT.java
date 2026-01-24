package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.ComputedEdgeRegistry;
import com.e2eq.ontology.core.EdgeChanges;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.mongo.OntologyWriteHook;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.service.OntologyReindexer;
import dev.morphia.MorphiaDatastore;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration test for Territory/Location/Associate hierarchy
 * with computed edge provider functionality.
 *
 * <p>Test Scenario:</p>
 * <ol>
 *   <li>Create hierarchical territories: West Region -> California -> Bay Area</li>
 *   <li>Assign locations to territories</li>
 *   <li>Assign an associate to a territory</li>
 *   <li>Verify computed "canSeeLocation" edges are created via ComputedEdgeProvider</li>
 *   <li>Add new locations to territories</li>
 *   <li>Run reindex and verify new edges are discovered</li>
 *   <li>Verify provenance tracking for computed edges</li>
 * </ol>
 *
 * <p>Key relationships:</p>
 * <ul>
 *   <li>Territory -> parentTerritory -> Territory (hierarchy)</li>
 *   <li>Territory -> containsLocations -> TerritoryLocation[] (explicit)</li>
 *   <li>TerritoryAssociate -> assignedTerritories -> Territory[] (explicit)</li>
 *   <li>TerritoryAssociate -> canSeeLocation -> TerritoryLocation[] (computed/derived)</li>
 * </ul>
 */
@QuarkusTest
public class TerritoryHierarchyComputedIT {

    private static final String REALM = "territory-hierarchy-test";

    @Inject
    MorphiaDataStoreWrapper dataStoreWrapper;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    OntologyWriteHook writeHook;

    @Inject
    OntologyReindexer reindexer;

    @Inject
    ComputedEdgeRegistry computedEdgeRegistry;

    @Inject
    AssociateCanSeeLocationProvider provider;  // CDI-managed instance

    private DataDomain testDataDomain;
    private MorphiaDatastore datastore;

    // Map of territory refName -> Territory for hierarchy loader
    private Map<String, Territory> territoryMap = new HashMap<>();

    @BeforeEach
    void setup() {
        // Get datastore for the specific realm (database)
        datastore = dataStoreWrapper.getDataStore(REALM);

        // Create test DataDomain for isolated realm
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("territory-test-org");
        testDataDomain.setAccountNum("8888888888");
        testDataDomain.setTenantId(REALM);
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);

        // Ensure a ResourceContext is active for MorphiaRepo operations
        SecurityContext.setResourceContext(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);

        // Clean all test collections for this realm
        edgeRepo.deleteAll(REALM);
        datastore.getDatabase().getCollection("it_territories").deleteMany(new Document());
        datastore.getDatabase().getCollection("it_territory_locations").deleteMany(new Document());
        datastore.getDatabase().getCollection("it_territory_associates").deleteMany(new Document());

        // Clear territory map
        territoryMap.clear();

        // Configure the CDI-managed provider with loaders for test data
        provider.setTerritoryLoader(this::loadTerritory);
        provider.setChildTerritoryLoader(this::getChildTerritories);

        // Register with ComputedEdgeRegistry (if not already registered)
        computedEdgeRegistry.register(provider);

        Log.info("Test setup complete for TerritoryHierarchyComputedIT");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Territory/Location Loader Functions (simulating database queries)
    // ─────────────────────────────────────────────────────────────────────────

    private Optional<Territory> loadTerritory(String territoryId) {
        // First try by ID, then by refName
        for (Territory t : territoryMap.values()) {
            if (t.getId() != null && t.getId().toString().equals(territoryId)) {
                return Optional.of(t);
            }
            if (t.getRefName() != null && t.getRefName().equals(territoryId)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    private List<Territory> getChildTerritories(String parentId) {
        List<Territory> children = new ArrayList<>();
        for (Territory t : territoryMap.values()) {
            if (t.getParentTerritory() != null) {
                String parentTerritoryId = t.getParentTerritory().getId() != null
                        ? t.getParentTerritory().getId().toString()
                        : t.getParentTerritory().getRefName();
                if (parentId.equals(parentTerritoryId)) {
                    children.add(t);
                    // Recursively add children of children
                    String childId = t.getId() != null ? t.getId().toString() : t.getRefName();
                    children.addAll(getChildTerritories(childId));
                }
            }
        }
        return children;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Create territory hierarchy with locations and verify explicit edges")
    void createTerritoryHierarchyAndVerifyExplicitEdges() {
        // === Create Locations ===
        TerritoryLocation sanFrancisco = createLocation("LOC-SF", "San Francisco", "San Francisco", "CA");
        TerritoryLocation losAngeles = createLocation("LOC-LA", "Los Angeles", "Los Angeles", "CA");
        TerritoryLocation seattle = createLocation("LOC-SEA", "Seattle", "Seattle", "WA");

        // === Create Territory Hierarchy ===
        // West Region (root)
        //   -> California (sub-region, contains SF and LA)
        //   -> Washington (sub-region, contains Seattle)

        Territory westRegion = createTerritory("TERR-WEST", "West Region", null);

        Territory california = createTerritory("TERR-CA", "California", westRegion);
        california.addLocation(sanFrancisco);
        california.addLocation(losAngeles);
        datastore.save(california);
        writeHook.afterPersist(REALM, california);
        territoryMap.put(california.getRefName(), california);

        Territory washington = createTerritory("TERR-WA", "Washington", westRegion);
        washington.addLocation(seattle);
        datastore.save(washington);
        writeHook.afterPersist(REALM, washington);
        territoryMap.put(washington.getRefName(), washington);

        // Verify explicit edges for California -> Locations
        List<OntologyEdge> caEdges = edgeRepo.findBySrc(testDataDomain, california.getId().toString());
        Log.infof("California edges: %s", edgeList(caEdges));

        // Should have parentTerritory edge and containsLocations edges
        boolean hasParentEdge = caEdges.stream()
                .anyMatch(e -> "parentTerritory".equals(e.getP()) && westRegion.getId().toString().equals(e.getDst()));
        assertTrue(hasParentEdge, "California should have parentTerritory edge to West Region");

        long locationEdgeCount = caEdges.stream()
                .filter(e -> "containsLocations".equals(e.getP()))
                .count();
        assertEquals(2, locationEdgeCount, "California should have 2 containsLocations edges (SF and LA)");

        // Verify Washington has location edge
        List<OntologyEdge> waEdges = edgeRepo.findBySrc(testDataDomain, washington.getId().toString());
        boolean hasSeattleEdge = waEdges.stream()
                .anyMatch(e -> "containsLocations".equals(e.getP()) && seattle.getId().toString().equals(e.getDst()));
        assertTrue(hasSeattleEdge, "Washington should have containsLocations edge to Seattle");

        Log.info("Territory hierarchy and explicit edges verified successfully");
    }

    @Test
    @DisplayName("Associate assigned to territory gets computed canSeeLocation edges")
    void associateAssignedToTerritoryGetsComputedEdges() {
        // === Create Locations ===
        TerritoryLocation sanFrancisco = createLocation("LOC-SF", "San Francisco", "San Francisco", "CA");
        TerritoryLocation losAngeles = createLocation("LOC-LA", "Los Angeles", "Los Angeles", "CA");

        // === Create Territory with Locations ===
        Territory california = createTerritory("TERR-CA", "California", null);
        california.addLocation(sanFrancisco);
        california.addLocation(losAngeles);
        datastore.save(california);
        writeHook.afterPersist(REALM, california);
        territoryMap.put(california.getRefName(), california);

        // === Create Associate assigned to California ===
        TerritoryAssociate john = new TerritoryAssociate("ASSOC-JOHN", "John", "Doe", "john.doe@example.com");
        john.setDataDomain(testDataDomain);
        john.addTerritory(california);
        datastore.save(john);
        writeHook.afterPersist(REALM, john);

        Log.infof("Created associate %s with assigned territory %s", john.getId(), california.getId());

        // === Verify Computed Edges ===
        List<OntologyEdge> johnEdges = edgeRepo.findBySrc(testDataDomain, john.getId().toString());
        Log.infof("John's edges: %s", edgeList(johnEdges));

        // Should have assignedTerritories edge
        boolean hasAssignedTerritory = johnEdges.stream()
                .anyMatch(e -> "assignedTerritories".equals(e.getP()) && california.getId().toString().equals(e.getDst()));
        assertTrue(hasAssignedTerritory, "John should have assignedTerritories edge to California");

        // Should have computed canSeeLocation edges
        Set<String> canSeeLocations = johnEdges.stream()
                .filter(e -> "canSeeLocation".equals(e.getP()))
                .map(OntologyEdge::getDst)
                .collect(Collectors.toSet());

        Log.infof("John's canSeeLocation targets: %s", canSeeLocations);

        assertTrue(canSeeLocations.contains(sanFrancisco.getId().toString()),
                "John should have canSeeLocation edge to San Francisco");
        assertTrue(canSeeLocations.contains(losAngeles.getId().toString()),
                "John should have canSeeLocation edge to Los Angeles");

        // Verify computed edges are marked as derived
        List<OntologyEdge> derivedEdges = johnEdges.stream()
                .filter(e -> "canSeeLocation".equals(e.getP()) && e.isDerived())
                .toList();
        assertEquals(2, derivedEdges.size(), "Both canSeeLocation edges should be derived");

        // Verify provenance
        for (OntologyEdge derived : derivedEdges) {
            assertNotNull(derived.getProv(), "Derived edge should have provenance");
            assertEquals("computed", derived.getProv().get("rule"), "Provenance rule should be 'computed'");
            // Provider ID is stored inside the "inputs" map
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = (Map<String, Object>) derived.getProv().get("inputs");
            assertNotNull(inputs, "Provenance should have inputs");
            assertEquals("AssociateCanSeeLocationProvider", inputs.get("providerId"),
                    "Provenance should identify the provider");
            Log.infof("Computed edge %s -> %s provenance: %s", derived.getP(), derived.getDst(), derived.getProv());
        }

        Log.info("Computed canSeeLocation edges verified successfully");
    }

    @Test
    @DisplayName("Associate with territory hierarchy gets edges to all locations in sub-territories")
    void associateWithHierarchySeesAllSubTerritoryLocations() {
        // === Create Locations ===
        TerritoryLocation sanFrancisco = createLocation("LOC-SF", "San Francisco", "San Francisco", "CA");
        TerritoryLocation losAngeles = createLocation("LOC-LA", "Los Angeles", "Los Angeles", "CA");
        TerritoryLocation seattle = createLocation("LOC-SEA", "Seattle", "Seattle", "WA");
        TerritoryLocation denver = createLocation("LOC-DEN", "Denver", "Denver", "CO");

        // === Create Territory Hierarchy ===
        // West Region (root) - Denver at this level
        //   -> California (SF, LA)
        //   -> Washington (Seattle)

        Territory westRegion = createTerritory("TERR-WEST", "West Region", null);
        westRegion.addLocation(denver);  // Denver is directly under West Region
        datastore.save(westRegion);
        writeHook.afterPersist(REALM, westRegion);
        territoryMap.put(westRegion.getRefName(), westRegion);

        Territory california = createTerritory("TERR-CA", "California", westRegion);
        california.addLocation(sanFrancisco);
        california.addLocation(losAngeles);
        datastore.save(california);
        writeHook.afterPersist(REALM, california);
        territoryMap.put(california.getRefName(), california);

        Territory washington = createTerritory("TERR-WA", "Washington", westRegion);
        washington.addLocation(seattle);
        datastore.save(washington);
        writeHook.afterPersist(REALM, washington);
        territoryMap.put(washington.getRefName(), washington);

        // === Create Associate assigned to West Region (root) ===
        TerritoryAssociate manager = new TerritoryAssociate("ASSOC-MGR", "Regional", "Manager", "manager@example.com");
        manager.setDataDomain(testDataDomain);
        manager.addTerritory(westRegion);
        datastore.save(manager);
        writeHook.afterPersist(REALM, manager);

        Log.infof("Created manager %s assigned to West Region", manager.getId());

        // === Verify Manager can see ALL locations in hierarchy ===
        List<OntologyEdge> mgrEdges = edgeRepo.findBySrc(testDataDomain, manager.getId().toString());
        Log.infof("Manager's edges: %s", edgeList(mgrEdges));

        Set<String> canSeeLocations = mgrEdges.stream()
                .filter(e -> "canSeeLocation".equals(e.getP()))
                .map(OntologyEdge::getDst)
                .collect(Collectors.toSet());

        Log.infof("Manager can see locations: %s", canSeeLocations);

        // Manager should see all 4 locations from the hierarchy
        assertTrue(canSeeLocations.contains(denver.getId().toString()),
                "Manager should see Denver (direct under West Region)");
        assertTrue(canSeeLocations.contains(sanFrancisco.getId().toString()),
                "Manager should see San Francisco (via California)");
        assertTrue(canSeeLocations.contains(losAngeles.getId().toString()),
                "Manager should see Los Angeles (via California)");
        assertTrue(canSeeLocations.contains(seattle.getId().toString()),
                "Manager should see Seattle (via Washington)");

        assertEquals(4, canSeeLocations.size(), "Manager should see exactly 4 locations");

        Log.info("Hierarchy-based computed edges verified successfully");
    }

    @Test
    @DisplayName("Add new location to territory and verify reindex creates new computed edges")
    void addLocationAndReindexCreatesNewComputedEdges() throws Exception {
        // === Phase 1: Create initial setup ===
        TerritoryLocation sanFrancisco = createLocation("LOC-SF", "San Francisco", "San Francisco", "CA");

        Territory california = createTerritory("TERR-CA", "California", null);
        california.addLocation(sanFrancisco);
        datastore.save(california);
        writeHook.afterPersist(REALM, california);
        territoryMap.put(california.getRefName(), california);

        TerritoryAssociate john = new TerritoryAssociate("ASSOC-JOHN", "John", "Doe", "john@example.com");
        john.setDataDomain(testDataDomain);
        john.addTerritory(california);
        datastore.save(john);
        writeHook.afterPersist(REALM, john);

        // Verify initial state: John sees only SF
        List<OntologyEdge> initialEdges = edgeRepo.findBySrc(testDataDomain, john.getId().toString());
        long initialLocationCount = initialEdges.stream()
                .filter(e -> "canSeeLocation".equals(e.getP()))
                .count();
        assertEquals(1, initialLocationCount, "Initially John should see 1 location (SF)");

        // === Phase 2: Add new location to territory OUT-OF-BAND ===
        TerritoryLocation losAngeles = createLocation("LOC-LA", "Los Angeles", "Los Angeles", "CA");

        // Reload California and add LA without triggering writeHook
        Territory caReloaded = datastore.find(Territory.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", california.getId()))
                .first();
        assertNotNull(caReloaded, "Should be able to reload California");

        caReloaded.addLocation(losAngeles);
        datastore.save(caReloaded);
        // NOTE: Intentionally NOT calling writeHook.afterPersist here (simulating out-of-band update)
        territoryMap.put(caReloaded.getRefName(), caReloaded);  // Update map

        Log.info("Added Los Angeles to California out-of-band (without writeHook)");

        // Verify John doesn't see LA yet
        List<OntologyEdge> beforeReindex = edgeRepo.findBySrc(testDataDomain, john.getId().toString());
        boolean hasLABefore = beforeReindex.stream()
                .anyMatch(e -> "canSeeLocation".equals(e.getP()) && losAngeles.getId().toString().equals(e.getDst()));
        assertFalse(hasLABefore, "Before reindex, John should NOT see Los Angeles");

        // === Phase 3: Run reindex ===
        Log.info("Running reindex...");
        reindexer.runAsync(REALM, true);
        waitUntilCompleted(30000);

        var result = reindexer.getResult();
        Log.infof("Reindex status: %s, added: %d, removed: %d",
                result.status(), result.summary().totalAdded(), result.summary().totalRemoved());

        assertThat(result.status(), startsWith("COMPLETED"));

        // === Phase 4: Verify John now sees LA ===
        List<OntologyEdge> afterReindex = edgeRepo.findBySrc(testDataDomain, john.getId().toString());
        Log.infof("After reindex, John's edges: %s", edgeList(afterReindex));

        Set<String> locationsAfter = afterReindex.stream()
                .filter(e -> "canSeeLocation".equals(e.getP()))
                .map(OntologyEdge::getDst)
                .collect(Collectors.toSet());

        assertTrue(locationsAfter.contains(sanFrancisco.getId().toString()),
                "After reindex, John should still see San Francisco");
        assertTrue(locationsAfter.contains(losAngeles.getId().toString()),
                "After reindex, John should now see Los Angeles");

        assertEquals(2, locationsAfter.size(), "John should see 2 locations after reindex");

        Log.info("Reindex successfully created new computed edges");
    }

    @Test
    @DisplayName("Verify reindex is idempotent for computed edges")
    void verifyReindexIdempotencyForComputedEdges() throws Exception {
        // === Create setup ===
        TerritoryLocation location1 = createLocation("LOC-1", "Location 1", "City 1", "ST");
        TerritoryLocation location2 = createLocation("LOC-2", "Location 2", "City 2", "ST");

        Territory territory = createTerritory("TERR-1", "Test Territory", null);
        territory.addLocation(location1);
        territory.addLocation(location2);
        datastore.save(territory);
        writeHook.afterPersist(REALM, territory);
        territoryMap.put(territory.getRefName(), territory);

        TerritoryAssociate assoc = new TerritoryAssociate("ASSOC-1", "Test", "Associate", "test@example.com");
        assoc.setDataDomain(testDataDomain);
        assoc.addTerritory(territory);
        datastore.save(assoc);
        writeHook.afterPersist(REALM, assoc);

        // First reindex
        reindexer.runAsync(REALM, true);
        waitUntilCompleted(30000);

        List<OntologyEdge> afterFirst = edgeRepo.findBySrc(testDataDomain, assoc.getId().toString());
        int countAfterFirst = afterFirst.size();
        long computedCountFirst = afterFirst.stream()
                .filter(e -> "canSeeLocation".equals(e.getP()))
                .count();

        Log.infof("After first reindex: %d edges, %d computed", countAfterFirst, computedCountFirst);

        // Second reindex
        reindexer.runAsync(REALM, false);
        waitUntilCompleted(30000);

        List<OntologyEdge> afterSecond = edgeRepo.findBySrc(testDataDomain, assoc.getId().toString());
        int countAfterSecond = afterSecond.size();
        long computedCountSecond = afterSecond.stream()
                .filter(e -> "canSeeLocation".equals(e.getP()))
                .count();

        Log.infof("After second reindex: %d edges, %d computed", countAfterSecond, computedCountSecond);

        // Verify idempotency
        assertEquals(countAfterFirst, countAfterSecond, "Total edge count should be same after second reindex");
        assertEquals(computedCountFirst, computedCountSecond, "Computed edge count should be same");

        // Verify no duplicate edges
        Map<String, Long> edgeCounts = afterSecond.stream()
                .collect(Collectors.groupingBy(e -> e.getP() + "|" + e.getDst(), Collectors.counting()));
        for (var entry : edgeCounts.entrySet()) {
            assertEquals(1L, entry.getValue().longValue(),
                    "Should have no duplicate edges: " + entry.getKey());
        }

        Log.info("Reindex idempotency verified for computed edges");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    private TerritoryLocation createLocation(String refName, String name, String city, String state) {
        TerritoryLocation loc = new TerritoryLocation(refName, name, city, state);
        loc.setDataDomain(testDataDomain);
        datastore.save(loc);
        writeHook.afterPersist(REALM, loc);
        Log.infof("Created location: %s (%s)", refName, loc.getId());
        return loc;
    }

    private Territory createTerritory(String refName, String name, Territory parent) {
        Territory territory = new Territory(refName, name);
        territory.setDataDomain(testDataDomain);
        territory.setParentTerritory(parent);
        datastore.save(territory);
        writeHook.afterPersist(REALM, territory);
        territoryMap.put(refName, territory);
        Log.infof("Created territory: %s (%s), parent=%s", refName, territory.getId(),
                parent != null ? parent.getRefName() : "none");
        return territory;
    }

    private String edgeList(List<OntologyEdge> edges) {
        return edges.stream()
                .map(e -> String.format("[%s%s]%s->%s",
                        e.isInferred() ? "INF" : (e.isDerived() ? "DRV" : "EXP"),
                        e.getProv() != null ? "+" : "",
                        e.getP(), e.getDst()))
                .collect(Collectors.joining(", "));
    }

    private void waitUntilCompleted(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = reindexer.status();
            if (!reindexer.isRunning() && (status.startsWith("COMPLETED") || status.startsWith("FAILED"))) {
                if (status.startsWith("FAILED")) {
                    fail("Reindex failed: " + status);
                }
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Reindexer did not complete in time; status=" + reindexer.status());
    }
}
