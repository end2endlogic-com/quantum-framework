package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.DependsOn.Expand;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.testkit.ComputedEdgeProviderHarness;
import com.e2eq.ontology.testkit.InMemoryHierarchy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loads a YAML containing a {@code computed:} block, builds a
 * {@link DeclarativeComputedEdgeProvider} against in-memory fakes, and
 * verifies edges round-trip end-to-end.
 */
class DeclarativeComputedEdgeTest {

    @OntologyClass(id = "Associate")
    public static class Associate {
        public final String id;
        public final List<String> assignedTerritoryIds;
        public Associate(String id, List<String> ids) { this.id = id; this.assignedTerritoryIds = ids; }
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

    @Test
    void parsesComputedBlockFromYaml() throws Exception {
        YamlOntologyLoader loader = new YamlOntologyLoader();
        List<ComputedEdgeSpec> specs = loader.loadComputedSpecsFromClasspath("/computed-edges-example.yaml");

        assertEquals(1, specs.size());
        ComputedEdgeSpec s = specs.get(0);
        assertEquals("canSeeLocation", s.id());
        assertEquals("Associate", s.domain());
        assertEquals("Location", s.range());
        assertEquals("Territory", s.hierarchy().nodeType());
        assertEquals("assignedTerritories", s.hierarchy().fromField());
        assertEquals(Expand.DESCENDANTS, s.hierarchy().expand());
        assertEquals("locationLists", s.list().field());
        assertEquals(List.of("Territory", "LocationList"), s.dependsOnTypes());
    }

    @Test
    void declarativeProviderProducesExpectedEdges() throws Exception {
        YamlOntologyLoader loader = new YamlOntologyLoader();
        ComputedEdgeSpec spec = loader.loadComputedSpecsFromClasspath("/computed-edges-example.yaml").get(0);

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

        DeclarativeRuntime<Associate, Territory, Location> runtime =
                new DeclarativeRuntime<>() {
                    @Override public boolean supports(ComputedEdgeSpec s) { return "canSeeLocation".equals(s.id()); }
                    @Override public List<String> assignedNodeIds(Associate a) { return a.assignedTerritoryIds; }
                    @Override public Optional<Territory> loadHierarchyNode(String id) { return world.load(id); }
                    @Override public List<Territory> walkHierarchy(String id, Expand expand) {
                        return expand == Expand.DESCENDANTS ? world.descendants(id) : List.of();
                    }
                    @Override public List<Location> resolveListItems(Territory node) { return world.itemsOf(node.getId()); }
                    @Override public String extractTargetId(Location t) { return t.getId(); }
                    @Override public Class<Associate> getSourceType() { return Associate.class; }
                };

        var provider = new DeclarativeComputedEdgeProvider<>(spec, runtime);
        Associate john = new Associate("john", List.of("west"));

        var result = ComputedEdgeProviderHarness.invoke(provider, john);
        assertEquals(Set.of("sf", "la", "oak"), result.targetIds());
        assertEquals("declarative:canSeeLocation", provider.getProviderId());
    }

    @Test
    void unknownExpandValueIsRejected() {
        YamlOntologyLoader loader = new YamlOntologyLoader();
        String yaml = """
                computed:
                  - id: bad
                    domain: A
                    range: B
                    via:
                      hierarchy:
                        node: Node
                        from: f
                        expand: sideways
                """;
        Exception e = assertThrows(RuntimeException.class,
                () -> loader.loadComputedSpecs(new java.io.ByteArrayInputStream(yaml.getBytes())));
        assertTrue(e.getMessage().contains("expand"));
    }

    @Test
    void emptyComputedBlockIsBenign() throws Exception {
        YamlOntologyLoader loader = new YamlOntologyLoader();
        String yaml = """
                version: 1
                classes:
                  - id: A
                """;
        List<ComputedEdgeSpec> specs = loader.loadComputedSpecs(new java.io.ByteArrayInputStream(yaml.getBytes()));
        assertTrue(specs.isEmpty());
    }
}
