package com.e2eq.ontology.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins Java/Python canonical-hash parity (Q1 in UNIFIED_ONTOLOGY_DESIGN.md):
 * the golden vector's expected hash is produced by the Python reference
 * implementation; this test must produce the identical bytes.
 */
class CanonicalTBoxHasherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    @Test
    void goldenVectorHashMatchesPythonReference() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/conformance-vectors/hash-golden.json")) {
            assertNotNull(in, "hash-golden.json vector missing from test resources");
            Map<String, Object> vector = MAPPER.readValue(in, Map.class);
            Map<String, Object> pack = (Map<String, Object>) vector.get("tbox");
            Map<String, Object> expected = (Map<String, Object>) vector.get("expected");

            String hash = CanonicalTBoxHasher.hashPackMapping(pack);

            assertEquals(expected.get("tbox_hash"), hash,
                    "Canonical hash drifted from the Python reference (see HASH_SPEC.md)");
        }
    }

    @Test
    void recordBasedHashMatchesPackMappingHash() {
        // Same vocabulary expressed through the Java records + pack metadata
        // must hash identically to the authored pack form.
        Map<String, OntologyRegistry.ClassDef> classes = Map.of(
                "Person", new OntologyRegistry.ClassDef("Person", Set.of(), Set.of(), Set.of()),
                "Adult", new OntologyRegistry.ClassDef("Adult", Set.of("Person"), Set.of(), Set.of()));
        Map<String, OntologyRegistry.PropertyDef> properties = Map.of(
                "knows", new OntologyRegistry.PropertyDef("knows", Optional.of("Person"), Optional.of("Person"),
                        false, Optional.empty(), false, true, false, Set.of(), false));
        OntologyRegistry.TBox tbox = new OntologyRegistry.TBox(classes, properties, List.of());

        String fromRecords = CanonicalTBoxHasher.hashTBox(tbox,
                new CanonicalTBoxHasher.PackMetadata("parity", "Parity", 1, "closed", null, null));
        String fromMapping = CanonicalTBoxHasher.hashPackMapping(Map.of(
                "version", 1,
                "id", "parity",
                "name", "Parity",
                "openness", "closed",
                "classes", List.of(
                        Map.of("id", "Person"),
                        Map.of("id", "Adult", "parents", List.of("Person"))),
                "properties", List.of(
                        Map.of("id", "knows", "domain", "Person", "range", "Person", "symmetric", true))));

        assertEquals(fromMapping, fromRecords);
    }

    @Test
    void opennessAliasesNormalizeLikePython() {
        assertEquals("closed", CanonicalTBoxHasher.normalizeOpenness("snapshot"));
        assertEquals("closed", CanonicalTBoxHasher.normalizeOpenness("factual"));
        assertEquals("open", CanonicalTBoxHasher.normalizeOpenness(null));
        assertEquals("open", CanonicalTBoxHasher.normalizeOpenness("discovery"));
    }
}
