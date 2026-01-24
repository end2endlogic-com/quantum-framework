package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.ontology.core.ComputedEdgeRegistry;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.mongo.OntologyWriteHook;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.MorphiaDatastore;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating permission-based filtering of locations using
 * ontology edges with proper policy integration.
 *
 * <p>This test demonstrates the complete flow:</p>
 * <ol>
 *   <li>Associates are assigned to Territories</li>
 *   <li>Territories contain Locations (and have hierarchy)</li>
 *   <li>ComputedEdgeProvider creates "canSeeLocation" edges from Associate to Location</li>
 *   <li>A permission rule uses hasIncomingEdge(canSeeLocation, $userId) to filter</li>
 *   <li>The standard getList API automatically applies the filter</li>
 * </ol>
 *
 * <p>Key pattern:</p>
 * <ul>
 *   <li>Rule: andFilterString = "hasIncomingEdge(canSeeLocation, ${userId})"</li>
 *   <li>SecurityCallScope establishes principal context with userId = associateId</li>
 *   <li>MorphiaRepo.getList automatically filters via RuleContext.getFilters()</li>
 * </ul>
 */
@QuarkusTest
public class TerritoryLocationPermissionIT {

    private static final String REALM = "territory-permission-test";

    @Inject
    MorphiaDataStoreWrapper dataStoreWrapper;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    OntologyWriteHook writeHook;

    @Inject
    ComputedEdgeRegistry computedEdgeRegistry;

    @Inject
    AssociateCanSeeLocationProvider provider;

    @Inject
    TerritoryLocationRepo locationRepo;

    @Inject
    RuleContext ruleContext;

    private DataDomain testDataDomain;
    private MorphiaDatastore datastore;

    // Map of territory refName -> Territory for hierarchy loader
    private Map<String, Territory> territoryMap = new HashMap<>();

    @BeforeEach
    void setup() {
        datastore = dataStoreWrapper.getDataStore(REALM);

        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("permission-test-org");
        testDataDomain.setAccountNum("9999999999");
        testDataDomain.setTenantId(REALM);
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);

        // Clean all test collections
        edgeRepo.deleteAll(REALM);
        datastore.getDatabase().getCollection("it_territories").deleteMany(new Document());
        datastore.getDatabase().getCollection("it_territory_locations").deleteMany(new Document());
        datastore.getDatabase().getCollection("it_territory_associates").deleteMany(new Document());

        territoryMap.clear();

        // Configure provider with loaders
        provider.setTerritoryLoader(this::loadTerritory);
        provider.setChildTerritoryLoader(this::getChildTerritories);
        computedEdgeRegistry.register(provider);

        // Clear any existing rules
        ruleContext.clear();

        Log.info("Test setup complete for TerritoryLocationPermissionIT");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Territory Loader Functions
    // ─────────────────────────────────────────────────────────────────────────

    private Optional<Territory> loadTerritory(String territoryId) {
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
    @DisplayName("Associate can only see locations via policy with hasIncomingEdge filter")
    void associateSeesOnlyAuthorizedLocationsViaPolicy() {
        // === Create Locations ===
        TerritoryLocation sanFrancisco = createLocation("LOC-SF", "San Francisco", "San Francisco", "CA");
        TerritoryLocation losAngeles = createLocation("LOC-LA", "Los Angeles", "Los Angeles", "CA");
        TerritoryLocation seattle = createLocation("LOC-SEA", "Seattle", "Seattle", "WA");

        // === Create Territories ===
        Territory california = createTerritory("TERR-CA", "California", null);
        california.addLocation(sanFrancisco);
        california.addLocation(losAngeles);
        datastore.save(california);
        writeHook.afterPersist(REALM, california);
        territoryMap.put(california.getRefName(), california);

        Territory washington = createTerritory("TERR-WA", "Washington", null);
        washington.addLocation(seattle);
        datastore.save(washington);
        writeHook.afterPersist(REALM, washington);
        territoryMap.put(washington.getRefName(), washington);

        // === Create Associates ===
        TerritoryAssociate johnCalifornia = new TerritoryAssociate("ASSOC-JOHN", "John", "Doe", "john@example.com");
        johnCalifornia.setDataDomain(testDataDomain);
        johnCalifornia.addTerritory(california);
        datastore.save(johnCalifornia);
        writeHook.afterPersist(REALM, johnCalifornia);

        TerritoryAssociate janeWashington = new TerritoryAssociate("ASSOC-JANE", "Jane", "Smith", "jane@example.com");
        janeWashington.setDataDomain(testDataDomain);
        janeWashington.addTerritory(washington);
        datastore.save(janeWashington);
        writeHook.afterPersist(REALM, janeWashington);

        Log.infof("Created John (California) and Jane (Washington) associates");

        // Verify computed edges were created
        List<OntologyEdge> johnEdges = edgeRepo.findBySrc(testDataDomain, johnCalifornia.getId().toString());
        Log.infof("John's edges: %s", edgeList(johnEdges));

        // === Register permission rule with hasIncomingEdge filter ===
        // This rule filters locations to only those the current user (identified by userId) can see
        Rule locationPermissionRule = createLocationPermissionRule();
        ruleContext.addRule(locationPermissionRule.getSecurityURI().getHeader(), locationPermissionRule);

        // === Test: John queries locations via getList with policy filter ===
        String johnId = johnCalifornia.getId().toString();
        List<TerritoryLocation> johnLocations = queryLocationsAsUser(johnId);

        Log.infof("John's visible locations via policy: %s",
                johnLocations.stream().map(TerritoryLocation::getName).collect(Collectors.toList()));

        // === Verify John sees only California locations ===
        assertEquals(2, johnLocations.size(), "John should see exactly 2 locations (CA)");
        assertTrue(johnLocations.stream().anyMatch(l -> "San Francisco".equals(l.getName())),
                "John should see San Francisco");
        assertTrue(johnLocations.stream().anyMatch(l -> "Los Angeles".equals(l.getName())),
                "John should see Los Angeles");
        assertFalse(johnLocations.stream().anyMatch(l -> "Seattle".equals(l.getName())),
                "John should NOT see Seattle");

        // === Test: Jane queries locations via getList with policy filter ===
        String janeId = janeWashington.getId().toString();
        List<TerritoryLocation> janeLocations = queryLocationsAsUser(janeId);

        Log.infof("Jane's visible locations via policy: %s",
                janeLocations.stream().map(TerritoryLocation::getName).collect(Collectors.toList()));

        // === Verify Jane sees only Washington locations ===
        assertEquals(1, janeLocations.size(), "Jane should see exactly 1 location (WA)");
        assertTrue(janeLocations.stream().anyMatch(l -> "Seattle".equals(l.getName())),
                "Jane should see Seattle");

        Log.info("Policy-based filtering with hasIncomingEdge verified successfully");
    }

    @Test
    @DisplayName("Associate with multiple territories sees all authorized locations")
    void associateWithMultipleTerritoriesSeesAllAuthorizedLocations() {
        // === Create Locations ===
        TerritoryLocation sanFrancisco = createLocation("LOC-SF", "San Francisco", "San Francisco", "CA");
        TerritoryLocation seattle = createLocation("LOC-SEA", "Seattle", "Seattle", "WA");
        TerritoryLocation denver = createLocation("LOC-DEN", "Denver", "Denver", "CO");

        // === Create Territories ===
        Territory california = createTerritory("TERR-CA", "California", null);
        california.addLocation(sanFrancisco);
        datastore.save(california);
        writeHook.afterPersist(REALM, california);
        territoryMap.put(california.getRefName(), california);

        Territory washington = createTerritory("TERR-WA", "Washington", null);
        washington.addLocation(seattle);
        datastore.save(washington);
        writeHook.afterPersist(REALM, washington);
        territoryMap.put(washington.getRefName(), washington);

        Territory colorado = createTerritory("TERR-CO", "Colorado", null);
        colorado.addLocation(denver);
        datastore.save(colorado);
        writeHook.afterPersist(REALM, colorado);
        territoryMap.put(colorado.getRefName(), colorado);

        // === Create Regional Manager with multiple territories ===
        TerritoryAssociate manager = new TerritoryAssociate("ASSOC-MGR", "Regional", "Manager", "manager@example.com");
        manager.setDataDomain(testDataDomain);
        manager.addTerritory(california);
        manager.addTerritory(washington);
        // Note: NOT assigned to Colorado
        datastore.save(manager);
        writeHook.afterPersist(REALM, manager);

        Log.infof("Created Regional Manager assigned to California and Washington");

        // Register permission rule
        Rule locationPermissionRule = createLocationPermissionRule();
        ruleContext.addRule(locationPermissionRule.getSecurityURI().getHeader(), locationPermissionRule);

        // === Test: Manager queries locations via getList with policy filter ===
        String managerId = manager.getId().toString();
        List<TerritoryLocation> managerLocations = queryLocationsAsUser(managerId);

        Log.infof("Manager's visible locations via policy: %s",
                managerLocations.stream().map(TerritoryLocation::getName).collect(Collectors.toList()));

        // === Verify Manager sees CA and WA locations, but not CO ===
        assertEquals(2, managerLocations.size(), "Manager should see 2 locations (CA + WA)");
        assertTrue(managerLocations.stream().anyMatch(l -> "San Francisco".equals(l.getName())),
                "Manager should see San Francisco");
        assertTrue(managerLocations.stream().anyMatch(l -> "Seattle".equals(l.getName())),
                "Manager should see Seattle");
        assertFalse(managerLocations.stream().anyMatch(l -> "Denver".equals(l.getName())),
                "Manager should NOT see Denver");

        Log.info("Multi-territory policy-based filtering verified successfully");
    }

    @Test
    @DisplayName("Associate with no territory sees no locations (fail-closed)")
    void associateWithNoTerritorySeesNothing() {
        // === Create Locations ===
        TerritoryLocation sanFrancisco = createLocation("LOC-SF", "San Francisco", "San Francisco", "CA");

        // === Create Territory ===
        Territory california = createTerritory("TERR-CA", "California", null);
        california.addLocation(sanFrancisco);
        datastore.save(california);
        writeHook.afterPersist(REALM, california);
        territoryMap.put(california.getRefName(), california);

        // === Create Associate with NO territory assignment ===
        TerritoryAssociate unassigned = new TerritoryAssociate("ASSOC-UNASSIGNED", "Unassigned", "User", "unassigned@example.com");
        unassigned.setDataDomain(testDataDomain);
        datastore.save(unassigned);
        writeHook.afterPersist(REALM, unassigned);

        // Register permission rule
        Rule locationPermissionRule = createLocationPermissionRule();
        ruleContext.addRule(locationPermissionRule.getSecurityURI().getHeader(), locationPermissionRule);

        // === Test: Unassigned user queries locations ===
        String unassignedId = unassigned.getId().toString();
        List<TerritoryLocation> locations = queryLocationsAsUser(unassignedId);

        Log.infof("Unassigned user's visible locations: %s",
                locations.stream().map(TerritoryLocation::getName).collect(Collectors.toList()));

        // === Verify unassigned user sees nothing (fail-closed) ===
        assertEquals(0, locations.size(), "Unassigned user should see no locations");

        Log.info("Fail-closed policy behavior verified successfully");
    }

    @Test
    @DisplayName("Policy filter combined with additional query criteria")
    void policyFilterCombinedWithQueryCriteria() {
        // === Create Locations with different names ===
        TerritoryLocation sfOffice = createLocation("LOC-SF-OFF", "San Francisco Office", "San Francisco", "CA");
        TerritoryLocation laWarehouse = createLocation("LOC-LA-WH", "Los Angeles Warehouse", "Los Angeles", "CA");
        TerritoryLocation sdOffice = createLocation("LOC-SD-OFF", "San Diego Office", "San Diego", "CA");

        // === Create Territory ===
        Territory california = createTerritory("TERR-CA", "California", null);
        california.addLocation(sfOffice);
        california.addLocation(laWarehouse);
        california.addLocation(sdOffice);
        datastore.save(california);
        writeHook.afterPersist(REALM, california);
        territoryMap.put(california.getRefName(), california);

        // === Create Associate ===
        TerritoryAssociate john = new TerritoryAssociate("ASSOC-JOHN", "John", "Doe", "john@example.com");
        john.setDataDomain(testDataDomain);
        john.addTerritory(california);
        datastore.save(john);
        writeHook.afterPersist(REALM, john);

        // Register permission rule
        Rule locationPermissionRule = createLocationPermissionRule();
        ruleContext.addRule(locationPermissionRule.getSecurityURI().getHeader(), locationPermissionRule);

        String johnId = john.getId().toString();

        // === Test: Query with additional filter (name contains "Office") ===
        // The policy filter AND the business filter should both apply
        List<TerritoryLocation> officeLocations = queryLocationsAsUserWithQuery(johnId, "name:*Office*");

        Log.infof("John's visible Office locations: %s",
                officeLocations.stream().map(TerritoryLocation::getName).collect(Collectors.toList()));

        // === Verify only Office locations returned ===
        assertEquals(2, officeLocations.size(), "Should find 2 Office locations John can see");
        assertTrue(officeLocations.stream().anyMatch(l -> l.getName().contains("San Francisco")),
                "Should include San Francisco Office");
        assertTrue(officeLocations.stream().anyMatch(l -> l.getName().contains("San Diego")),
                "Should include San Diego Office");
        assertFalse(officeLocations.stream().anyMatch(l -> l.getName().contains("Warehouse")),
                "Should NOT include Los Angeles Warehouse");

        Log.info("Combined policy and business filter verified successfully");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a permission rule that filters locations using hasIncomingEdge.
     * The filter: hasIncomingEdge(canSeeLocation, ${userId})
     * Meaning: Only return locations that the current user has canSeeLocation edges TO.
     */
    private Rule createLocationPermissionRule() {
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity("SALES_REP")  // Applies to users with SALES_REP role
                .withArea("territory-test")
                .withFunctionalDomain("location")
                .withAction("list")
                .build();

        SecurityURIBody body = new SecurityURIBody.Builder()
                .withRealm(REALM)
                .withOrgRefName("*")
                .withAccountNumber("*")
                .withTenantId("*")
                .withOwnerId("*")
                .build();

        SecurityURI uri = new SecurityURI(header, body);

        return new Rule.Builder()
                .withName("location-territory-permission")
                .withDescription("Filter locations by territory assignment using canSeeLocation edges")
                .withSecurityURI(uri)
                .withEffect(RuleEffect.ALLOW)
                .withPriority(5)
                .withFinalRule(true)
                // The key filter: hasIncomingEdge finds destinations (locations) that the source (userId) points to
                .withAndFilterString("hasIncomingEdge(canSeeLocation,${principalId})")
                .build();
    }

    /**
     * Query locations as a specific user using SecurityCallScope and the standard getList API.
     */
    private List<TerritoryLocation> queryLocationsAsUser(String userId) {
        return queryLocationsAsUserWithQuery(userId, null);
    }

    /**
     * Query locations as a specific user with an optional additional query filter.
     */
    private List<TerritoryLocation> queryLocationsAsUserWithQuery(String userId, String additionalQuery) {
        // Create principal context with the user ID (associate ID)
        PrincipalContext principal = new PrincipalContext.Builder()
                .withDefaultRealm(REALM)
                .withDataDomain(testDataDomain)
                .withUserId(userId)
                .withRoles(new String[]{"sales_rep"})
                .withScope("USER")
                .build();

        // Create resource context for list action on locations
        ResourceContext resource = new ResourceContext.Builder()
                .withRealm(REALM)
                .withArea("territory-test")
                .withFunctionalDomain("location")
                .withAction("list")
                .build();

        // Run the query within the security scope
        return SecurityCallScope.runWithContexts(principal, resource, () -> {
            // Use getListByQuery which automatically applies permission filters from RuleContext
            return locationRepo.getListByQuery(REALM, 0, 100, additionalQuery, null, null);
        });
    }

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
}
