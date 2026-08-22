package com.e2eq.framework.rest.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omitsAbsentOptionalPropertiesFromSerializedPayload() throws Exception {
        AuthResponse response = new AuthResponse("access", "refresh", 123L);

        JsonNode payload = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertEquals("access", payload.get("access_token").asText());
        assertEquals("refresh", payload.get("refresh_token").asText());
        assertEquals(123L, payload.get("expires_at").asLong());
        assertEquals("access", payload.get("accessToken").asText());
        assertEquals("refresh", payload.get("refreshToken").asText());
        assertEquals(123L, payload.get("expiresAt").asLong());
        assertFalse(payload.has("mongodburl"));
        assertFalse(payload.has("roles"));
        assertFalse(payload.has("authProvider"));
        assertFalse(payload.has("applications"));
        assertFalse(payload.has("activeApplication"));
        assertFalse(payload.has("activeApplicationId"));
        assertFalse(payload.has("accessibleRealms"));
        assertFalse(payload.has("accessibleTenants"));
    }

    @Test
    void serializesNewAndLegacyActiveApplicationNames() throws Exception {
        AuthResponse response = new AuthResponse("access", "refresh", 123L);
        response.setActiveApplicationId("worker-platform");

        JsonNode payload = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertEquals("worker-platform", payload.get("activeApplicationId").asText());
        assertEquals("worker-platform", payload.get("activeApplication").asText());

        AuthResponse legacy = objectMapper.readValue(
                "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_at\":1,"
                        + "\"activeApplication\":\"legacy-app\"}",
                AuthResponse.class);
        assertEquals("legacy-app", legacy.getActiveApplicationId());
    }

    @Test
    void serializesAccessibleRealmOptionalDateAsStringAndOmitsNullSummary() throws Exception {
        AccessibleRealmInfo realm = new AccessibleRealmInfo("demo", "Demo");
        realm.setSetupLastUpdated(new Date(0));
        AuthResponse response = new AuthResponse("access", "refresh", 123L);
        response.setRoles(List.of("admin"));
        response.setAuthProvider("custom");
        response.setAccessibleRealms(List.of(realm));

        JsonNode payload = objectMapper.readTree(objectMapper.writeValueAsString(response));
        JsonNode realmPayload = payload.get("accessibleRealms").get(0);

        assertEquals("admin", payload.get("roles").get(0).asText());
        assertEquals("custom", payload.get("authProvider").asText());
        assertEquals("demo", realmPayload.get("refName").asText());
        assertTrue(realmPayload.get("setupLastUpdated").isTextual());
        assertFalse(realmPayload.has("setupSummary"));
    }

    @Test
    void advertisesAccessibleRealmTimestampAsOpenApiDateTime() throws Exception {
        Schema schema = AccessibleRealmInfo.class
                .getDeclaredField("setupLastUpdated")
                .getAnnotation(Schema.class);

        assertEquals(SchemaType.STRING, schema.type());
        assertEquals("date-time", schema.format());
    }
}
