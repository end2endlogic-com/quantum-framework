package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.OntologyClass;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the cycle / maxDepth / maxTargets guards on {@link HierarchyListEdgeProvider}.
 */
class HierarchyListGuardsTest {

    @OntologyClass(id = "TestSrc")
    static class Src {
        final String id;
        final List<String> nodeIds;
        Src(String id, List<String> nodeIds) { this.id = id; this.nodeIds = nodeIds; }
        public String getId() { return id; }
    }

    static class Node {
        final String id;
        final List<String> targets;
        Node(String id, List<String> targets) { this.id = id; this.targets = targets; }
        public String getId() { return id; }
    }

    /** Hierarchy provider over an in-memory node tree configured per test. */
    static class TestProvider extends HierarchyListEdgeProvider<Src, Node, String> {
        final Map<String, Node> nodes;
        final Map<String, List<String>> children;

        TestProvider(Map<String, Node> nodes, Map<String, List<String>> children) {
            this.nodes = nodes; this.children = children;
        }

        @Override public Class<Src> getSourceType() { return Src.class; }
        @Override public String getPredicate() { return "p"; }
        @Override public String getTargetTypeName() { return "T"; }
        @Override protected String getHierarchyTypeName() { return "Node"; }
        @Override protected List<String> getAssignedNodeIds(Src source) { return source.nodeIds; }
        @Override protected Optional<Node> loadHierarchyNode(ComputationContext ctx, String id) {
            return Optional.ofNullable(nodes.get(id));
        }
        @Override protected List<Node> getChildNodes(ComputationContext ctx, String id) {
            // Flat descendants, as the contract requires.
            List<Node> out = new ArrayList<>();
            Deque<String> stack = new ArrayDeque<>(children.getOrDefault(id, List.of()));
            Set<String> seen = new HashSet<>();
            while (!stack.isEmpty()) {
                String c = stack.pop();
                if (!seen.add(c)) continue; // local dedup so the test feeder doesn't loop
                Node n = nodes.get(c);
                if (n != null) out.add(n);
                stack.addAll(children.getOrDefault(c, List.of()));
            }
            return out;
        }
        @Override protected List<String> resolveListItems(ComputationContext ctx, Node node) {
            return node.targets;
        }
        @Override protected String extractTargetId(String target) { return target; }
    }

    @Test
    void cycleGuardSkipsRevisitedAssignment() {
        Map<String, Node> nodes = Map.of(
                "n1", new Node("n1", List.of("t1"))
        );
        TestProvider p = new TestProvider(nodes, Map.of());

        // n1 listed twice as an assignment → second visit is the cycle.
        Src src = new Src("s1", List.of("n1", "n1"));

        var ctx = new ComputedEdgeProvider.ComputationContext("realm", null, "TestProvider");
        Set<ComputedEdgeProvider.ComputedTarget> targets = p.computeTargets(ctx, src);

        assertEquals(1, targets.size());
        assertTrue(ctx.tripped("cycle"));
    }

    @Test
    void maxTargetsCapTruncatesOutput() {
        Map<String, Node> nodes = Map.of(
                "n1", new Node("n1", List.of("a", "b", "c", "d", "e"))
        );
        TestProvider p = new TestProvider(nodes, Map.of());
        Src src = new Src("s1", List.of("n1"));

        var ctx = new ComputedEdgeProvider.ComputationContext("realm", null, "TestProvider");
        ctx.setMaxComputedTargets(3);

        Set<ComputedEdgeProvider.ComputedTarget> targets = p.computeTargets(ctx, src);
        // Implementation breaks AFTER processing a node, so cap is "high-water" not strict.
        assertTrue(ctx.tripped("maxTargets"));
        assertTrue(targets.size() >= 3);
    }

    @Test
    void maxHierarchyDepthCountsNodesProcessed() {
        // Long chain: n0 -> n1 -> n2 -> n3 -> n4 (each as descendants of n0).
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, List<String>> kids = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            nodes.put("n" + i, new Node("n" + i, List.of("t" + i)));
            if (i < 4) kids.put("n" + i, List.of("n" + (i + 1)));
        }
        TestProvider p = new TestProvider(nodes, kids);
        Src src = new Src("s1", List.of("n0"));

        var ctx = new ComputedEdgeProvider.ComputationContext("realm", null, "TestProvider");
        ctx.setMaxHierarchyDepth(2); // process at most 2 hierarchy nodes

        Set<ComputedEdgeProvider.ComputedTarget> targets = p.computeTargets(ctx, src);

        assertTrue(ctx.tripped("maxDepth"));
        assertEquals(2, targets.size(), "should have produced exactly two targets before tripping");
    }

    @Test
    void noGuardsTrippedOnNormalRun() {
        Map<String, Node> nodes = Map.of(
                "n1", new Node("n1", List.of("t1"))
        );
        TestProvider p = new TestProvider(nodes, Map.of());
        Src src = new Src("s1", List.of("n1"));

        var ctx = new ComputedEdgeProvider.ComputationContext("realm", null, "TestProvider");
        Set<ComputedEdgeProvider.ComputedTarget> targets = p.computeTargets(ctx, src);

        assertEquals(1, targets.size());
        assertTrue(ctx.getGuardTrips().isEmpty());
    }
}
