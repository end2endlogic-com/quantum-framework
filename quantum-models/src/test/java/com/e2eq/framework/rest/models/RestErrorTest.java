package com.e2eq.framework.rest.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RestErrorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omitsAbsentOptionalPropertiesFromSerializedPayload() throws Exception {
        RestError error = RestError.builder()
                .status(404)
                .statusMessage("Not found")
                .build();

        JsonNode payload = objectMapper.readTree(objectMapper.writeValueAsString(error));

        assertEquals(404, payload.get("status").asInt());
        assertEquals("Not found", payload.get("statusMessage").asText());
        assertFalse(payload.has("reasonMessage"));
        assertFalse(payload.has("debugMessage"));
        assertFalse(payload.has("constraintViolations"));
    }

    @Test
    void retainsOptionalPropertiesWhenTheyArePresent() throws Exception {
        RestError error = RestError.builder()
                .status(400)
                .statusMessage("Validation failed")
                .reasonMessage("Invalid request")
                .debugMessage("requestId=123")
                .constraintViolations(Set.of("name must not be blank"))
                .build();

        JsonNode payload = objectMapper.readTree(objectMapper.writeValueAsString(error));

        assertEquals("Invalid request", payload.get("reasonMessage").asText());
        assertEquals("requestId=123", payload.get("debugMessage").asText());
        assertEquals("name must not be blank", payload.get("constraintViolations").get(0).asText());
    }
}
