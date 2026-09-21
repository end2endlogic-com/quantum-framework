package com.e2eq.ontology.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ScriptHelpersTest {

    private Map<String, Object> bindings;
    private Map<String, Object> rcontext;
    private List<Map<String, Object>> edges;

    @BeforeEach
    void setUp() {
        bindings = new HashMap<>();
        rcontext = new HashMap<>();
        edges = new ArrayList<>();
        rcontext.put("edges", edges);
        bindings.put("rcontext", rcontext);

        // Populate sample edges
        Map<String, Object> edge1 = new HashMap<>();
        edge1.put("src", "u1");
        edge1.put("p", "assignedTo");
        edge1.put("dst", "loc1");
        edge1.put("inferred", false);
        edge1.put("props", Map.of("role", "PRIMARY", "status", "ACTIVE", "level", 2));

        Map<String, Object> edge2 = new HashMap<>();
        edge2.put("src", "u1");
        edge2.put("p", "assignedTo");
        edge2.put("dst", "loc2");
        edge2.put("inferred", false);
        edge2.put("props", Map.of("role", "BACKUP", "status", "INACTIVE", "level", 1));

        Map<String, Object> edge3 = new HashMap<>();
        edge3.put("src", "u2");
        edge3.put("p", "canAccess");
        edge3.put("dst", "doc1");
        edge3.put("inferred", true);
        edge3.put("props", Map.of("clearance", "TOP_SECRET"));

        edges.add(edge1);
        edges.add(edge2);
        edges.add(edge3);

        ScriptHelpers.install(bindings);
    }

    @Test
    void testHasEdgeWithoutFilter() {
        ScriptHelpers.EdgeHelper<String, String, Boolean> hasEdge =
                (ScriptHelpers.EdgeHelper<String, String, Boolean>) bindings.get("hasEdge");

        // 2-arg calls
        assertTrue(hasEdge.apply("assignedTo", "loc1"));
        assertTrue(hasEdge.apply("assignedTo", "loc2"));
        assertFalse(hasEdge.apply("assignedTo", "loc3"));
        assertTrue(hasEdge.apply("assignedTo", null)); // any dst for predicate
        assertFalse(hasEdge.apply("nonExistent", null));
    }

    @Test
    void testHasEdgeWithMapFilter() {
        ScriptHelpers.EdgeHelper<String, String, Boolean> hasEdge =
                (ScriptHelpers.EdgeHelper<String, String, Boolean>) bindings.get("hasEdge");

        // Single property filter
        assertTrue(hasEdge.apply("assignedTo", "loc1", Map.of("role", "PRIMARY")));
        assertFalse(hasEdge.apply("assignedTo", "loc1", Map.of("role", "BACKUP")));

        // Multiple property filter
        assertTrue(hasEdge.apply("assignedTo", "loc1", Map.of("role", "PRIMARY", "status", "ACTIVE")));
        assertFalse(hasEdge.apply("assignedTo", "loc1", Map.of("role", "PRIMARY", "status", "INACTIVE")));

        // Root field filter combined with props filter
        assertTrue(hasEdge.apply("assignedTo", "loc1", Map.of("role", "PRIMARY", "inferred", false)));
        assertFalse(hasEdge.apply("assignedTo", "loc1", Map.of("role", "PRIMARY", "inferred", true)));

        // Number property matching (numeric comparison)
        assertTrue(hasEdge.apply("assignedTo", "loc1", Map.of("level", 2.0)));
        assertTrue(hasEdge.apply("assignedTo", "loc1", Map.of("level", 2)));
        assertFalse(hasEdge.apply("assignedTo", "loc1", Map.of("level", 5)));

        // props. prefixed keys
        assertTrue(hasEdge.apply("assignedTo", "loc1", Map.of("props.role", "PRIMARY")));
    }

    @Test
    void testHasEdgeWithPredicateAndFunctionCallbacks() {
        ScriptHelpers.EdgeHelper<String, String, Boolean> hasEdge =
                (ScriptHelpers.EdgeHelper<String, String, Boolean>) bindings.get("hasEdge");

        Predicate<Map<String, Object>> pred = edge -> "PRIMARY".equals(edge.get("role"));
        assertTrue(hasEdge.apply("assignedTo", "loc1", pred));
        assertFalse(hasEdge.apply("assignedTo", "loc2", pred));

        Function<Map<String, Object>, Boolean> fn = edge -> "ACTIVE".equals(edge.get("status"));
        assertTrue(hasEdge.apply("assignedTo", "loc1", fn));
        assertFalse(hasEdge.apply("assignedTo", "loc2", fn));
    }

    @Test
    void testHasIncomingEdge() {
        ScriptHelpers.EdgeHelper<String, String, Boolean> hasIncomingEdge =
                (ScriptHelpers.EdgeHelper<String, String, Boolean>) bindings.get("hasIncomingEdge");

        // 2-arg backward compat
        assertTrue(hasIncomingEdge.apply("assignedTo", "u1"));
        assertFalse(hasIncomingEdge.apply("assignedTo", "unknownUser"));

        // 3-arg with map filter
        assertTrue(hasIncomingEdge.apply("canAccess", "u2", Map.of("clearance", "TOP_SECRET")));
        assertFalse(hasIncomingEdge.apply("canAccess", "u2", Map.of("clearance", "CONFIDENTIAL")));
    }

    @Test
    void testHasAnyEdgeWithFilter() {
        ScriptHelpers.EdgeCollectionHelper<String, Collection<String>, Boolean> hasAnyEdge =
                (ScriptHelpers.EdgeCollectionHelper<String, Collection<String>, Boolean>) bindings.get("hasAnyEdge");

        // Without filter (2-arg)
        assertTrue(hasAnyEdge.apply("assignedTo", List.of("loc1", "loc99")));
        assertFalse(hasAnyEdge.apply("assignedTo", List.of("loc88", "loc99")));

        // With filter (3-arg)
        assertTrue(hasAnyEdge.apply("assignedTo", List.of("loc1", "loc2"), Map.of("role", "PRIMARY")));
        assertFalse(hasAnyEdge.apply("assignedTo", List.of("loc2"), Map.of("role", "PRIMARY")));
    }

    @Test
    void testHasAllEdgesWithFilter() {
        ScriptHelpers.EdgeCollectionHelper<String, Collection<String>, Boolean> hasAllEdges =
                (ScriptHelpers.EdgeCollectionHelper<String, Collection<String>, Boolean>) bindings.get("hasAllEdges");

        assertTrue(hasAllEdges.apply("assignedTo", List.of("loc1", "loc2")));
        assertFalse(hasAllEdges.apply("assignedTo", List.of("loc1", "loc3")));

        // With filter
        assertTrue(hasAllEdges.apply("assignedTo", List.of("loc1"), Map.of("role", "PRIMARY")));
        assertFalse(hasAllEdges.apply("assignedTo", List.of("loc1", "loc2"), Map.of("role", "PRIMARY")));
    }

    @Test
    void testRelatedIdsWithFilter() {
        ScriptHelpers.RelatedIdsHelper<String, List<String>> relatedIds =
                (ScriptHelpers.RelatedIdsHelper<String, List<String>>) bindings.get("relatedIds");

        // 1-arg without filter
        List<String> allLocs = relatedIds.apply("assignedTo");
        assertEquals(List.of("loc1", "loc2"), allLocs);

        // 2-arg with filter
        List<String> primaryLocs = relatedIds.apply("assignedTo", Map.of("role", "PRIMARY"));
        assertEquals(List.of("loc1"), primaryLocs);

        List<String> backupLocs = relatedIds.apply("assignedTo", Map.of("role", "BACKUP"));
        assertEquals(List.of("loc2"), backupLocs);

        List<String> none = relatedIds.apply("assignedTo", Map.of("role", "NON_EXISTENT"));
        assertTrue(none.isEmpty());
    }

    @Test
    void testEdgeViewPropertiesAccess() {
        Map<String, Object> edge = new HashMap<>();
        edge.put("src", "u1");
        edge.put("dst", "loc1");
        edge.put("p", "assignedTo");
        edge.put("props", Map.of("role", "PRIMARY", "status", "ACTIVE"));

        Map<String, Object> view = ScriptHelpers.createEdgeView(edge);
        assertEquals("u1", view.get("src"));
        assertEquals("PRIMARY", view.get("role"));
        assertEquals("PRIMARY", view.get("props.role"));
        assertEquals("ACTIVE", view.get("status"));
        assertNull(view.get("nonExistent"));

        assertTrue(view.containsKey("src"));
        assertTrue(view.containsKey("role"));
        assertTrue(view.containsKey("props.role"));
        assertFalse(view.containsKey("nonExistent"));
    }
}
