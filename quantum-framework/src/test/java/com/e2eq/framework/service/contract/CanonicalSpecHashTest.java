package com.e2eq.framework.service.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cross-language pin: the expected values below were produced by
 * helixor-sdk-gen's Python canonicalization
 * ({@code json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=True)}
 * + SHA-256). If this test fails after a change here or in helixor-sdk-gen,
 * the two implementations have diverged — that is a contract break, not a
 * test to update casually; bump {@link CanonicalSpecHash#ALGORITHM} in both.
 */
class CanonicalSpecHashTest {

    // Computed by helixor_sdk_gen.openapi_ir.canonical_spec_sha256 (2026-07-20).
    private static final String PYTHON_PINNED_HASH =
        "03ecefbd400ba4487e98707e9015a85fcf0dcc366cae51842e5e40e10b405b0a";

    private static final String PYTHON_PINNED_CANONICAL =
        "{\"info\":{\"title\":\"Contract Pin \\u2014 \\u00e9\\u2713\",\"version\":\"1.2.3\"},"
            + "\"openapi\":\"3.1.0\",\"paths\":{\"/security/login\":{\"post\":{\"operationId\":\"login\","
            + "\"responses\":{\"200\":{\"description\":\"ok \\\"quoted\\\" \\\\ tab\\tnewline\\n\"}}}}},"
            + "\"x-bool\":true,\"x-int\":42,\"x-list\":[1,2,{\"a\":\"z\",\"z\":\"a\"}],\"x-null\":null}";

    @Test
    void matchesPythonCanonicalizationByteForByte() throws Exception {
        // Parse the pinned canonical form with keys deliberately reordered so the
        // test proves sorting, escaping, and separators — not input ordering.
        String reordered = "{\"x-null\":null,\"x-list\":[1,2,{\"z\":\"a\",\"a\":\"z\"}],\"x-int\":42,"
            + "\"x-bool\":true,\"paths\":{\"/security/login\":{\"post\":{\"responses\":"
            + "{\"200\":{\"description\":\"ok \\\"quoted\\\" \\\\ tab\\tnewline\\n\"}},\"operationId\":\"login\"}}},"
            + "\"openapi\":\"3.1.0\",\"info\":{\"version\":\"1.2.3\",\"title\":\"Contract Pin \\u2014 \\u00e9\\u2713\"}}";
        JsonNode spec = new ObjectMapper().readTree(reordered);

        assertEquals(PYTHON_PINNED_CANONICAL, CanonicalSpecHash.canonicalize(spec));
        assertEquals(PYTHON_PINNED_HASH, CanonicalSpecHash.sha256(spec));
    }

    @Test
    void yamlAndJsonFormsOfTheSameContractHashIdentically() throws Exception {
        String yaml = """
            openapi: "3.1.0"
            info:
              version: "1.2.3"
              title: Simple
            paths: {}
            """;
        String json = "{\"paths\":{},\"info\":{\"title\":\"Simple\",\"version\":\"1.2.3\"},\"openapi\":\"3.1.0\"}";
        JsonNode fromYaml = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        JsonNode fromJson = new ObjectMapper().readTree(json);
        assertEquals(CanonicalSpecHash.sha256(fromJson), CanonicalSpecHash.sha256(fromYaml));
    }

    @Test
    void nonIntegralNumbersFailFast() throws Exception {
        JsonNode spec = new ObjectMapper().readTree("{\"x\":1.5}");
        IllegalArgumentException error =
            assertThrows(IllegalArgumentException.class, () -> CanonicalSpecHash.sha256(spec));
        assertEquals(true, error.getMessage().contains("non-integral"));
    }
}
