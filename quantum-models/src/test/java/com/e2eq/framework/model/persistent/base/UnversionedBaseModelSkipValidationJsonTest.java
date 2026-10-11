package com.e2eq.framework.model.persistent.base;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code skipValidation} bypasses data-domain resolution and validation on persist, so it must never
 * be bound from external JSON while remaining settable by server code.
 */
class UnversionedBaseModelSkipValidationJsonTest {

    // Mirrors QuarkusJacksonCustomizer: unknown properties fail the request.
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    void readValue_ignores_skipValidation_true() throws Exception {
        String json = "{\"category\":\"c\",\"key\":\"k\",\"valueType\":\"STRING\",\"skipValidation\":true}";

        CodeList entity = mapper.readValue(json, CodeList.class);

        assertFalse(entity.isSkipValidation());
    }

    @Test
    void convertValue_ignores_skipValidation_true() {
        Map<String, Object> body = Map.of("category", "c", "key", "k", "valueType", "STRING", "skipValidation", true);

        CodeList entity = mapper.convertValue(body, CodeList.class);

        assertFalse(entity.isSkipValidation());
    }

    @Test
    void round_tripped_response_body_is_still_accepted() throws Exception {
        CodeList original = new CodeList();
        original.setKey("k");

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"skipValidation\":false"), "response shape keeps the read-only property: " + json);

        CodeList echoed = mapper.readValue(json, CodeList.class);
        assertFalse(echoed.isSkipValidation());
    }

    @Test
    void server_code_can_still_set_skipValidation() {
        CodeList entity = new CodeList();
        entity.setKey("k");

        entity.setSkipValidation(true);

        assertTrue(entity.isSkipValidation());
    }
}
