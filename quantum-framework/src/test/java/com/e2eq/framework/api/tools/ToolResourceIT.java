package com.e2eq.framework.api.tools;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for Tool Definition REST resource (/api/v1/ai/tools).
 */
@QuarkusTest
public class ToolResourceIT {

    @Test
    public void list_tools_returns_seeded_gateway_tools() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/v1/ai/tools")
        .then()
            .statusCode(200)
            .body("tools", notNullValue())
            .body("count", greaterThanOrEqualTo(6))
            .body("tools.refName", hasItems(
                "query_rootTypes", "query_plan", "query_find", "query_save", "query_delete", "query_deleteMany"));
    }

    @Test
    public void get_tool_by_ref_name_returns_tool() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/v1/ai/tools/query_find")
        .then()
            .statusCode(200)
            .body("refName", is("query_find"))
            .body("description", notNullValue())
            .body("toolType", is("QUANTUM_QUERY"));
    }

    @Test
    public void execute_query_root_types_returns_success() {
        given()
            .accept(ContentType.JSON)
            .contentType(ContentType.JSON)
            .body(Map.of())
        .when()
            .post("/api/v1/ai/tools/query_rootTypes/execute")
        .then()
            .statusCode(200)
            .body(notNullValue());
    }

    @Test
    public void count_tools_returns_count() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/v1/ai/tools/count")
        .then()
            .statusCode(200)
            .body("count", greaterThanOrEqualTo(0));
    }
}
