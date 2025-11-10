package com.e2eq.ontology.core;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class IncrementalChainEvaluatorTest {

    private OntologyRegistry registryForChains(boolean qFunctional, boolean qTransitive) {
        Map<String, OntologyRegistry.ClassDef> classes = Map.of(
                "A", new OntologyRegistry.ClassDef("A", Set.of(), Set.of(), Set.of()),
                "B", new OntologyRegistry.ClassDef("B", Set.of(), Set.of(), Set.of()),
                "C", new OntologyRegistry.ClassDef("C", Set.of(), Set.of(), Set.of())
        );
        Map<String, OntologyRegistry.PropertyDef> props = new HashMap<>();
        props.put("p1", new OntologyRegistry.PropertyDef("p1", Optional.of("A"), Optional.of("B"), false, Optional.empty(), false, false, false, Set.of()));
        props.put("p2", new OntologyRegistry.PropertyDef("p2", Optional.of("B"), Optional.of("C"), false, Optional.empty(), false, false, false, Set.of()));
        props.put("q", new OntologyRegistry.PropertyDef("q", Optional.of("A"), Optional.of("C"), false, Optional.empty(), qTransitive, false, qFunctional, Set.of()));
        List<OntologyRegistry.PropertyChainDef> chains = List.of(
                new OntologyRegistry.PropertyChainDef(List.of("p1", "p2"), "q")
        );
        return OntologyRegistry.inMemory(new OntologyRegistry.TBox(classes, props, chains));
    }

    @Test
    public void twoStepChain_dedup_minimalQueries() {
        String tenant = "t1";
        String X = "X"; String Y = "Y"; String Z = "Z";
        InMemoryEdgeStoreTestDouble store = new InMemoryEdgeStoreTestDouble();
        // Base graph: X -p1-> Y, Y -p2-> Z and a duplicate second path Y -p2-> Z
        store.upsert(tenant, "A", X, "p1", "B", Y, false, Map.of());
        store.upsert(tenant, "B", Y, "p2", "C", Z, false, Map.of());
        // add a duplicate edge shouldn't change result
        store.upsert(tenant, "B", Y, "p2", "C", Z, false, Map.of());

        IncrementalChainEvaluator eval = new IncrementalChainEvaluator();
        OntologyRegistry reg = registryForChains(false, false);
        IncrementalChainEvaluator.Result res = eval.evaluate(tenant, X, Set.of("p1"), reg, store);

        List<EdgeRecord> derived = res.derivedEdges();
        assertEquals(1, derived.size(), "should derive exactly one q(X,Z)");
        EdgeRecord e = derived.get(0);
        assertEquals(X, e.getSrc());
        assertEquals("q", e.getP());
        assertEquals(Z, e.getDst());

        // Minimal queries: listOutgoingBy should be called once for (X,p1) and once for (Y,p2)
        assertEquals(1, store.getQueryCount(X, "p1"));
        assertEquals(1, store.getQueryCount(Y, "p2"));
    }

    @Test
    public void functionalQ_prefersExistingExplicit() {
        String tenant = "t1";
        String X = "X"; String Y1 = "Y1"; String Y2 = "Y2"; String Z1 = "Z1"; String Z2 = "Z2";
        InMemoryEdgeStoreTestDouble store = new InMemoryEdgeStoreTestDouble();
        // Two paths produce candidates Z1 and Z2
        store.upsert(tenant, "A", X, "p1", "B", Y1, false, Map.of());
        store.upsert(tenant, "B", Y1, "p2", "C", Z1, false, Map.of());
        store.upsert(tenant, "A", X, "p1", "B", Y2, false, Map.of());
        store.upsert(tenant, "B", Y2, "p2", "C", Z2, false, Map.of());
        // Existing explicit q(X,Z2) should be preferred when q is functional
        store.upsert(tenant, "A", X, "q", "C", Z2, false, Map.of());

        OntologyRegistry reg = registryForChains(true, false);
        IncrementalChainEvaluator eval = new IncrementalChainEvaluator();
        IncrementalChainEvaluator.Result res = eval.evaluate(tenant, X, Set.of("p1", "p2"), reg, store);
        List<EdgeRecord> derived = res.derivedEdges();
        assertEquals(1, derived.size());
        assertEquals(Z2, derived.get(0).getDst(), "functional should prefer existing explicit");
    }

    @Test
    public void threeStepChain_middleIsImplied() {
        // p1,p2=>r and r,p3=>q ; test where p2 AND p3 are base, but r(Y,W) is implied
        // Here we simulate previously implied property r existing in the store (derived)
        String tenant = "t1";
        String X = "X"; String Y = "Y"; String W = "W"; String Z = "Z";
        InMemoryEdgeStoreTestDouble store = new InMemoryEdgeStoreTestDouble();

        // Registry: r is implied by p1,p2, and q is implied by r,p3
        Map<String, OntologyRegistry.ClassDef> classes = Map.of(
                "A", new OntologyRegistry.ClassDef("A", Set.of(), Set.of(), Set.of()),
                "B", new OntologyRegistry.ClassDef("B", Set.of(), Set.of(), Set.of()),
                "W", new OntologyRegistry.ClassDef("W", Set.of(), Set.of(), Set.of()),
                "C", new OntologyRegistry.ClassDef("C", Set.of(), Set.of(), Set.of())
        );
        Map<String, OntologyRegistry.PropertyDef> props = new HashMap<>();
        props.put("p1", new OntologyRegistry.PropertyDef("p1", Optional.of("A"), Optional.of("B"), false, Optional.empty(), false, false, false, Set.of()));
        props.put("p2", new OntologyRegistry.PropertyDef("p2", Optional.of("B"), Optional.of("W"), false, Optional.empty(), false, false, false, Set.of()));
        props.put("p3", new OntologyRegistry.PropertyDef("p3", Optional.of("W"), Optional.of("C"), false, Optional.empty(), false, false, false, Set.of()));
        props.put("r", new OntologyRegistry.PropertyDef("r", Optional.of("A"), Optional.of("W"), false, Optional.empty(), false, false, false, Set.of()));
        props.put("q", new OntologyRegistry.PropertyDef("q", Optional.of("A"), Optional.of("C"), false, Optional.empty(), false, false, false, Set.of()));
        List<OntologyRegistry.PropertyChainDef> chains = List.of(
                new OntologyRegistry.PropertyChainDef(List.of("p1", "p2"), "r"),
                new OntologyRegistry.PropertyChainDef(List.of("r", "p3"), "q")
        );
        OntologyRegistry reg = OntologyRegistry.inMemory(new OntologyRegistry.TBox(classes, props, chains));

        // Base explicit edges for around X
        store.upsert(tenant, "A", X, "p1", "B", Y, false, Map.of());
        store.upsert(tenant, "B", Y, "p2", "W", W, false, Map.of());
        // r is previously implied and present
        store.upsertDerived(tenant, "A", X, "r", "W", W, List.of(), Map.of());
        // final hop
        store.upsert(tenant, "W", W, "p3", "C", Z, false, Map.of());

        IncrementalChainEvaluator eval = new IncrementalChainEvaluator();
        IncrementalChainEvaluator.Result res = eval.evaluate(tenant, X, Set.of("p1", "p2", "p3", "r"), reg, store);

        List<EdgeRecord> derived = res.derivedEdges();
        boolean hasQ = derived.stream().anyMatch(e -> e.getSrc().equals(X) && e.getP().equals("q") && e.getDst().equals(Z));
        assertTrue(hasQ, "q(X,Z) should be derived via 3-step chain with previously implied middle r");

        // Query minimization: should query (X,p1), (Y,p2), (W,p3) only once each
        assertEquals(1, store.getQueryCount(X, "p1"));
        assertEquals(1, store.getQueryCount(Y, "p2"));
        assertEquals(1, store.getQueryCount(W, "p3"));
    }
}
