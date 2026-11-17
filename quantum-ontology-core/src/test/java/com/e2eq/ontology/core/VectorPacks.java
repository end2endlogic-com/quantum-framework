package com.e2eq.ontology.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Reads conformance vectors and turns their authored pack mappings into Java TBox records. */
final class VectorPacks {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VectorPacks() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> readVector(String name) throws Exception {
        try (InputStream in = VectorPacks.class.getResourceAsStream("/conformance-vectors/" + name + ".json")) {
            assertNotNull(in, "vector missing: " + name);
            return MAPPER.readValue(in, Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    static OntologyRegistry.TBox tboxFromPack(Map<String, Object> pack) {
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
            Boolean inferred = item.get("inferred") instanceof Boolean b ? b : null;
            properties.put(name, new OntologyRegistry.PropertyDef(name,
                    Optional.ofNullable((String) item.get("domain")),
                    Optional.ofNullable((String) item.get("range")),
                    inverseOf.isPresent(),
                    inverseOf,
                    Boolean.TRUE.equals(item.get("transitive")),
                    Boolean.TRUE.equals(item.get("symmetric")),
                    Boolean.TRUE.equals(item.get("functional")),
                    stringSet(item.get("subPropertyOf")),
                    RelationDerivation.resolveInferred(name, item.get("derivation"), inferred),
                    null, Set.of(), Map.of(),
                    RelationStability.parse(name, item.get("stability"))));
        }
        List<OntologyRegistry.PropertyChainDef> chains = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) pack.getOrDefault("chains", List.of())) {
            chains.add(new OntologyRegistry.PropertyChainDef(
                    (List<String>) item.get("chain"), (String) item.get("implies")));
        }
        return new OntologyRegistry.TBox(classes, properties, chains);
    }

    private static Set<String> stringSet(Object value) {
        Set<String> out = new HashSet<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
