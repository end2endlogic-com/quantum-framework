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
            "inverse", "subpropertyof", "symmetric", "transitive",
            "typing-precedence");

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

        OntologyRegistry.TBox tbox = tboxFromPack(pack);
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
        if ("closed".equals(openness) && (!unknownClasses.isEmpty() || !unknownPredicates.isEmpty())) {
            assertEquals("invalid", expected.get("status"), name + ": unexpected closed-mode rejection");
            assertEquals(expected.get("discovered_class_candidates"), new ArrayList<>(unknownClasses), name);
            assertEquals(expected.get("discovered_property_candidates"), new ArrayList<>(unknownPredicates), name);
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

    @SuppressWarnings("unchecked")
    private OntologyRegistry.TBox tboxFromPack(Map<String, Object> pack) {
        Map<String, OntologyRegistry.ClassDef> classes = new HashMap<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) pack.getOrDefault("classes", List.of())) {
            String name = (String) (item.get("id") != null ? item.get("id") : item.get("name"));
            classes.put(name, new OntologyRegistry.ClassDef(name,
                    stringSet(item.get("parents")), stringSet(item.get("disjointWith")), stringSet(item.get("sameAs"))));
        }
        Map<String, OntologyRegistry.PropertyDef> properties = new HashMap<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) pack.getOrDefault("properties", List.of())) {
            String name = (String) (item.get("id") != null ? item.get("id") : item.get("name"));
            Optional<String> inverseOf = Optional.ofNullable((String) item.get("inverseOf"));
            properties.put(name, new OntologyRegistry.PropertyDef(name,
                    Optional.ofNullable((String) item.get("domain")),
                    Optional.ofNullable((String) item.get("range")),
                    inverseOf.isPresent(),
                    inverseOf,
                    Boolean.TRUE.equals(item.get("transitive")),
                    Boolean.TRUE.equals(item.get("symmetric")),
                    Boolean.TRUE.equals(item.get("functional")),
                    stringSet(item.get("subPropertyOf")),
                    Boolean.TRUE.equals(item.get("inferred"))));
        }
        List<OntologyRegistry.PropertyChainDef> chains = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) pack.getOrDefault("chains", List.of())) {
            chains.add(new OntologyRegistry.PropertyChainDef(
                    (List<String>) item.get("chain"), (String) item.get("implies")));
        }
        return new OntologyRegistry.TBox(classes, properties, chains);
    }

    private Set<String> stringSet(Object value) {
        Set<String> out = new HashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
