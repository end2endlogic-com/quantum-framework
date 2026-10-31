package com.e2eq.framework.service.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Cross-language pin: expected hash produced by helixor-sdk-gen
 * {@code canonical_operation_sha256} in
 * {@code test_operation_hash_pin_matches_java_canonicalization}.
 */
class CanonicalOperationHashTest {

    private static final String PYTHON_PINNED_LOGIN_HASH =
        "79c40443aed51960eb05e4d057adb85ad4903717f2e65558b84ce5f7b73a435b";

    private static final String LOGIN_YAML = """
        openapi: "3.1.0"
        info:
          title: "Contract Pin — é✓"
          version: "2.1.0"
        paths:
          /security/login:
            post:
              operationId: login
              description: ignored documentation
              tags:
                - security
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      $ref: "#/components/schemas/AuthRequest"
              responses:
                "200":
                  description: "ok \\"quoted\\" \\\\ tab\\tnewline\\n"
                  content:
                    application/json:
                      schema:
                        $ref: "#/components/schemas/AuthResponse"
        components:
          schemas:
            AuthRequest:
              type: object
              title: ignored title
              required:
                - userId
                - password
              properties:
                userId:
                  type: string
                password:
                  type: string
            AuthResponse:
              type: object
              properties:
                access_token:
                  type: string
            Unused:
              type: object
              properties:
                extra:
                  type: string
        """;

    @Test
    void loginHashMatchesPythonPin() throws Exception {
        JsonNode spec = new ObjectMapper(new YAMLFactory()).readTree(LOGIN_YAML);
        assertEquals(PYTHON_PINNED_LOGIN_HASH, CanonicalOperationHash.sha256(spec, "POST", "/security/login"));
        assertEquals(
            PYTHON_PINNED_LOGIN_HASH,
            CanonicalOperationHash.sha256ByOperation(spec).get("POST /security/login").get("sha256"));
    }

    @Test
    void additiveUnrelatedPathDoesNotChangeLoginHash() throws Exception {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        JsonNode spec = yaml.readTree(LOGIN_YAML);
        String loginHash = CanonicalOperationHash.sha256(spec, "POST", "/security/login");

        com.fasterxml.jackson.databind.node.ObjectNode mutated = spec.deepCopy();
        mutated.with("info").put("version", "3.0.0");
        com.fasterxml.jackson.databind.node.ObjectNode contribution = mutated.with("paths")
            .putObject("/v1/identity-contributions")
            .putObject("post");
        contribution.put("operationId", "submitIdentityContribution");
        contribution.putObject("responses").putObject("200").put("description", "ok");
        mutated.with("components").with("schemas").with("AuthRequest").put("description", "still ignored");

        assertNotEquals(CanonicalSpecHash.sha256(spec), CanonicalSpecHash.sha256(mutated));
        assertEquals(loginHash, CanonicalOperationHash.sha256(mutated, "POST", "/security/login"));
    }
}
