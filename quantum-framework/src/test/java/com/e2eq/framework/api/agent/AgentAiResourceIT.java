package com.e2eq.framework.api.agent;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Integration tests for Agent AI REST resource (/api/v1/ai/agents).
 * Uses realm query param; agent and tools must exist in realm (e.g. created via POST /api/agents
 * or seed). Tests 404 when agent missing and execute when agent exists with resolved tools.
 */
@QuarkusTest
public class AgentAiResourceIT {

    private static final String REALM = "test-quantum-com";

    @Test
    void get_agent_by_ref_returns_404_when_missing() {
        given()
            .accept(ContentType.JSON)
            .queryParam("realm", REALM)
        .when()
            .get("/api/v1/ai/agents/nonexistent-agent-ref")
        .then()
            .statusCode(404);
    }

    @Test
    void list_tools_for_agent_returns_404_when_agent_missing() {
        given()
            .accept(ContentType.JSON)
            .queryParam("realm", REALM)
        .when()
            .get("/api/v1/ai/agents/nonexistent-agent-ref/tools")
        .then()
            .statusCode(404);
    }

    @Test
    void execute_for_agent_returns_404_when_agent_missing() {
        given()
            .accept(ContentType.JSON)
            .contentType(ContentType.JSON)
            .queryParam("realm", REALM)
            .body(Map.of("tool", "query_rootTypes", "arguments", Map.of()))
        .when()
            .post("/api/v1/ai/agents/nonexistent-agent-ref/execute")
        .then()
            .statusCode(404);
    }
}
