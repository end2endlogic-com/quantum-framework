package com.e2eq.ontology.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs the canonical TBox conformance vectors (repo-owned) against the Java
 * stack (Q2 in UNIFIED_ONTOLOGY_DESIGN.md): same TBox, same explicit edges,
 * same expected inferred-edge set as the Python MaterializationEngine. The
 * driver wraps the per-entity {@link ForwardChainingReasoner} in a graph
 * fixpoint and applies the closed-mode discovery gate the lite engine applies.
 */
class VectorConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> VECTORS = List.of(
            "chain", "closed-rejection", "combined", "hash-golden",
            "hash-golden-stability", "inverse", "stable-derived-explicit-rejection",
            "subpropertyof", "symmetric", "transitive", "typing-precedence");

    @TestFactory
    List<DynamicTest> conformanceVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String name : VECTORS) {
            tests.add(DynamicTest.dynamicTest(name, () -> runVector(name)));
        }
        return tests;
    }

    @SuppressWarnings("unchecked")
    private void runVector(String name) throws Exception {
        Map<String, Object> vector;
        try (InputStream in = getClass().getResourceAsStream("/conformance-vectors/" + name + ".json")) {
            assertNotNull(in, "vector missing: " + name);
            vector = MAPPER.readValue(in, Map.class);
        }
        Map<String, Object> pack = (Map<String, Object>) vector.get("tbox");
        Map<String, Object> expected = (Map<String, Object>) vector.get("expected");
        List<Map<String, Object>> explicitPayload = (List<Map<String, Object>>) vector.get("explicit_edges");

        OntologyRegistry.TBox tbox = VectorPacks.tboxFromPack(pack);
        InMemoryOntologyRegistry registry = new InMemoryOntologyRegistry(tbox);
        String openness = CanonicalTBoxHasher.normalizeOpenness((String) pack.get("openness"));

        Object expectedHash = expected.get("tbox_hash");
        if (expectedHash != null) {
            assertEquals(expectedHash, CanonicalTBoxHasher.hashPackMapping(pack),
                    name + ": canonical hash drift");
        }

        List<Reasoner.Edge> explicit = new ArrayList<>();
        Map<String, String> nodeTypes = new HashMap<>();
        for (Map<String, Object> edge : explicitPayload) {
            String src = (String) edge.get("src_id");
            String dst = (String) edge.get("dst_id");
            nodeTypes.putIfAbsent(src, (String) edge.get("src_type"));
            nodeTypes.putIfAbsent(dst, (String) edge.get("dst_type"));
            explicit.add(new Reasoner.Edge(src, (String) edge.get("src_type"), (String) edge.get("predicate"),
                    dst, (String) edge.get("dst_type"), false, Optional.empty()));
        }

        // Closed-mode discovery gate (mirrors the lite engine)
        Set<String> unknownClasses = new TreeSet<>();
        Set<String> unknownPredicates = new TreeSet<>();
        for (Reasoner.Edge edge : explicit) {
            if (!tbox.classes().containsKey(edge.srcType())) unknownClasses.add(edge.srcType());
            if (!tbox.classes().containsKey(edge.dstType())) unknownClasses.add(edge.dstType());
            if (!tbox.properties().containsKey(edge.p())) unknownPredicates.add(edge.p());
        }
        boolean closedRejected = "closed".equals(openness) && (!unknownClasses.isEmpty() || !unknownPredicates.isEmpty());
        // Explicit edges for stable derived relations are refused in open and closed mode alike, and
        // reported alongside any closed-mode rejection.
        List<String> rejectedDerived = StableRelationGuard.rejectedDerivedPredicates(registry,
                explicit.stream().map(Reasoner.Edge::p).toList());
        assertEquals(expected.getOrDefault("rejected_derived_predicates", List.of()), rejectedDerived,
                name + ": rejected stable derived predicates");
        if (!rejectedDerived.isEmpty()) {
            StableRelationGuard.StableDerivedEdgeException ex = assertThrows(
                    StableRelationGuard.StableDerivedEdgeException.class,
                    () -> new MaterializationEngine(registry).materialize("vector", "e", "T", explicit),
                    name + ": materialization accepted an explicit stable derived edge");
            assertEquals(rejectedDerived, ex.rejectedPredicates(), name);
        }
        if (closedRejected) {
            assertEquals(expected.get("discovered_class_candidates"), new ArrayList<>(unknownClasses), name);
            assertEquals(expected.get("discovered_property_candidates"), new ArrayList<>(unknownPredicates), name);
        }
        if (closedRejected || !rejectedDerived.isEmpty()) {
            assertEquals("invalid", expected.get("status"), name + ": unexpected rejection");
            assertEquals(List.of(), expected.get("inferred_edges"), name + ": a rejected vector infers nothing");
            return;
        }
        assertEquals("ok", expected.get("status"), name + ": expected rejection did not happen");

        Set<List<String>> inferred = fixpointInferred(registry, explicit, nodeTypes);
        Set<List<String>> expectedInferred = new HashSet<>();
        for (List<String> tuple : (List<List<String>>) expected.get("inferred_edges")) {
            expectedInferred.add(tuple);
        }
        assertEquals(expectedInferred, inferred, name + ": inferred edge set diverged from Python reference");
    }

    /** Graph-level fixpoint over the per-entity reasoner. */
    private Set<List<String>> fixpointInferred(OntologyRegistry registry, List<Reasoner.Edge> explicit,
                                               Map<String, String> nodeTypes) {
        ForwardChainingReasoner reasoner = new ForwardChainingReasoner();
        Map<List<String>, Reasoner.Edge> byKey = new LinkedHashMap<>();
        Set<List<String>> explicitKeys = new HashSet<>();
        for (Reasoner.Edge edge : explicit) {
            byKey.put(tripleKey(edge), edge);
            explicitKeys.add(tripleKey(edge));
        }
        for (int round = 0; round < 10; round++) {
            boolean changed = false;
            List<Reasoner.Edge> current = new ArrayList<>(byKey.values());
            for (String entityId : new TreeSet<>(nodeTypes.keySet())) {
                Reasoner.EntitySnapshot snapshot = new Reasoner.EntitySnapshot(
                        "vector", entityId, nodeTypes.get(entityId), current);
                Reasoner.InferenceResult result = reasoner.infer(snapshot, registry);
                for (Reasoner.Edge edge : result.addEdges()) {
                    List<String> key = tripleKey(edge);
                    if (!byKey.containsKey(key)) {
                        byKey.put(key, edge);
                        nodeTypes.putIfAbsent(edge.srcId(), edge.srcType());
                        nodeTypes.putIfAbsent(edge.dstId(), edge.dstType());
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        Set<List<String>> inferred = new HashSet<>();
        for (Map.Entry<List<String>, Reasoner.Edge> entry : byKey.entrySet()) {
            if (explicitKeys.contains(entry.getKey())) continue;
            Reasoner.Edge edge = entry.getValue();
            inferred.add(List.of(edge.srcId(), edge.srcType(), edge.p(), edge.dstId(), edge.dstType()));
        }
        return inferred;
    }

    private List<String> tripleKey(Reasoner.Edge edge) {
        return List.of(edge.srcId(), edge.p(), edge.dstId());
    }
}
