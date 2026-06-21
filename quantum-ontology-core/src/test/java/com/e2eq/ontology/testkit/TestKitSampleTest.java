package com.e2eq.ontology.testkit;

import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.core.ComputedEdgeProvider;
import com.e2eq.ontology.core.HierarchyListEdgeProvider;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end sample of the testkit: declare a provider against in-memory
 * fakes and assert on the produced edges with no Mongo, no Quarkus.
 */
class TestKitSampleTest {

    @OntologyClass(id = "Associate")
    public static class Associate {
        public final String id;
        public final List<String> assignedTerritoryIds;
        public Associate(String id, List<String> territories) {
            this.id = id; this.assignedTerritoryIds = territories;
        }
        public String getId() { return id; }
    }

    public static class Territory {
        public final String id;
        public Territory(String id) { this.id = id; }
        public String getId() { return id; }
    }

    public static class Location {
        public final String id;
        public Location(String id) { this.id = id; }
        public String getId() { return id; }
    }

    static class AssociateCanSeeLocation
            extends HierarchyListEdgeProvider<Associate, Territory, Location> {

        final InMemoryHierarchy<Territory, Location> world;

        AssociateCanSeeLocation(InMemoryHierarchy<Territory, Location> world) {
            this.world = world;
        }

        @Override public Class<Associate> getSourceType() { return Associate.class; }
        @Override public String getPredicate() { return "canSeeLocation"; }
        @Override public String getTargetTypeName() { return "Location"; }
        @Override protected String getHierarchyTypeName() { return "Territory"; }
        @Override protected List<String> getAssignedNodeIds(Associate s) { return s.assignedTerritoryIds; }
        @Override protected Optional<Territory> loadHierarchyNode(ComputationContext c, String id) {
            return world.load(id);
        }
        @Override protected List<Territory> getChildNodes(ComputationContext c, String id) {
            return world.descendants(id);
        }
        @Override protected List<Location> resolveListItems(ComputationContext c, Territory node) {
            return world.itemsOf(node.getId());
        }
        @Override protected String extractTargetId(Location target) { return target.getId(); }
    }

    @Test
    void associateSeesAllLocationsInAssignedTerritoryAndDescendants() {
        Territory west = new Territory("west");
        Territory ca = new Territory("ca");
        Territory bay = new Territory("bay");
        Location sf = new Location("sf");
        Location la = new Location("la");
        Location oak = new Location("oak");

        InMemoryHierarchy<Territory, Location> world =
                new InMemoryHierarchy<Territory, Location>(Territory::getId)
                        .add(west).add(ca).add(bay)
                        .link(west, ca).link(ca, bay)
                        .items(west, sf, la)
                        .items(ca, oak);

        Associate john = new Associate("john", List.of("west"));

        var result = ComputedEdgeProviderHarness.invoke(new AssociateCanSeeLocation(world), john);

        assertEquals(Set.of("sf", "la", "oak"), result.targetIds());
        assertEquals(Set.of("AssociateCanSeeLocation"), result.providerIds());
    }

    @Test
    void unknownAssignedTerritoryProducesNoEdges() {
        InMemoryHierarchy<Territory, Location> world =
                new InMemoryHierarchy<>(Territory::getId);
        Associate john = new Associate("john", List.of("nonexistent"));

        var result = ComputedEdgeProviderHarness.invoke(new AssociateCanSeeLocation(world), john);
        assertTrue(result.isEmpty());
    }
}
