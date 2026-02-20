package com.e2eq.framework.api.agent;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Agent configuration CRUD endpoints at /api/agent/config.
 * Tests run in order: create → get → list → delete → verify deletion.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AgentConfigResourceIT {

    private static final String REALM = "defaultRealm";
    private static final String REF_NAME = "test-supply-chain-agent";

    @Test
    @Order(1)
    public void save_agent_returns_200_with_id() {
        given()
            .contentType(ContentType.JSON)
            .queryParam("realm", REALM)
            .body("{" +
                "\"refName\":\"" + REF_NAME + "\"," +
                "\"name\":\"Supply Chain Assistant\"," +
                "\"llmConfigRef\":\"claude-sonnet\"," +
                "\"context\":[" +
                    "{\"order\":1,\"role\":\"system\",\"content\":\"You are a supply-chain analyst.\"}" +
                "]," +
                "\"enabledTools\":[\"query_rootTypes\",\"query_find\",\"query_plan\"]" +
            "}")
        .when()
            .post("/api/agent/config")
        .then()
            .statusCode(200)
            .body("id", notNullValue())
            .body("refName", is(REF_NAME))
            .body("name", is("Supply Chain Assistant"))
            .body("llmConfigRef", is("claude-sonnet"))
            .body("context.size()", is(1))
            .body("enabledTools.size()", is(3));
    }

    @Test
    @Order(2)
    public void get_by_refName_returns_saved_agent() {
        given()
            .accept(ContentType.JSON)
            .queryParam("realm", REALM)
        .when()
            .get("/api/agent/config/" + REF_NAME)
        .then()
            .statusCode(200)
            .body("refName", is(REF_NAME))
            .body("name", is("Supply Chain Assistant"))
            .body("llmConfigRef", is("claude-sonnet"))
            .body("context[0].role", is("system"))
            .body("enabledTools", hasItems("query_rootTypes", "query_find", "query_plan"));
    }

    @Test
    @Order(3)
    public void list_returns_agents_including_saved() {
        given()
            .accept(ContentType.JSON)
            .queryParam("realm", REALM)
        .when()
            .get("/api/agent/config/list")
        .then()
            .statusCode(200)
            .body("size()", greaterThan(0))
            .body("refName", hasItem(REF_NAME));
    }

    @Test
    @Order(4)
    public void delete_by_refName_returns_200() {
        given()
            .queryParam("realm", REALM)
        .when()
            .delete("/api/agent/config/" + REF_NAME)
        .then()
            .statusCode(200)
            .body("deleted", is(true));
    }

    @Test
    @Order(5)
    public void get_after_delete_returns_404() {
        given()
            .accept(ContentType.JSON)
            .queryParam("realm", REALM)
        .when()
            .get("/api/agent/config/" + REF_NAME)
        .then()
            .statusCode(404);
    }

    @Test
    public void missing_realm_returns_400() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/config/list")
        .then()
            .statusCode(400)
            .body("error", containsString("realm"));
    }
}
