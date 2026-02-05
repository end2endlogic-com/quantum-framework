package com.e2eq.framework.api.agent;

import com.e2eq.framework.model.persistent.base.CodeList;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for agent discovery and schema endpoints.
 */
@QuarkusTest
public class AgentResourceIT {

    @Test
    public void tools_endpoint_returns_six_gateway_tools() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/tools")
        .then()
            .statusCode(200)
            .body("tools", notNullValue())
            .body("tools.size()", is(6))
            .body("count", is(6))
            .body("tools.name", hasItems(
                "query_rootTypes", "query_plan", "query_find", "query_save", "query_delete", "query_deleteMany"));
    }

    @Test
    public void tools_endpoint_returns_expected_tool_fields() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/tools")
        .then()
            .statusCode(200)
            .body("tools[0].name", notNullValue())
            .body("tools[0].description", notNullValue())
            .body("tools[0].parameters", notNullValue())
            .body("tools[0].area", is("integration"))
            .body("tools[0].domain", is("query"));
    }

    @Test
    public void schema_endpoint_returns_root_types() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/schema")
        .then()
            .statusCode(200)
            .body("rootTypes", notNullValue())
            .body("rootTypes.size()", greaterThan(0))
            .body("count", greaterThan(0));
    }

    @Test
    public void schema_endpoint_includes_codelist() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/schema")
        .then()
            .statusCode(200)
            .body("rootTypes.className", hasItem(CodeList.class.getName()))
            .body("rootTypes.simpleName", hasItem("CodeList"));
    }

    @Test
    public void schema_for_root_type_returns_json_schema_like() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/schema/CodeList")
        .then()
            .statusCode(200)
            .body("type", is("object"))
            .body("title", is("CodeList"))
            .body("properties", notNullValue());
    }

    @Test
    public void schema_for_root_type_by_fqcn_returns_json_schema_like() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/schema/" + CodeList.class.getName())
        .then()
            .statusCode(200)
            .body("type", is("object"))
            .body("title", is("CodeList"))
            .body("properties", notNullValue());
    }

    @Test
    public void schema_for_unknown_root_type_returns_400() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/schema/NonExistentType")
        .then()
            .statusCode(400);
    }

    @Test
    public void execute_query_rootTypes_returns_root_types() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"tool\":\"query_rootTypes\",\"arguments\":{}}")
        .when()
            .post("/api/agent/execute")
        .then()
            .statusCode(200)
            .body("rootTypes", notNullValue())
            .body("count", greaterThan(0));
    }

    @Test
    public void execute_query_plan_returns_plan() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"tool\":\"query_plan\",\"arguments\":{\"rootType\":\"CodeList\",\"query\":\"refName:ACTIVE\"}}")
        .when()
            .post("/api/agent/execute")
        .then()
            .statusCode(200)
            .body("mode", notNullValue())
            .body("expandPaths", notNullValue());
    }

    @Test
    public void permission_hints_returns_check_evaluate_and_did_you_know() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/permission-hints")
        .then()
            .statusCode(200)
            .body("checkEndpoint", is("POST /system/permissions/check"))
            .body("evaluateEndpoint", is("POST /system/permissions/evaluate"))
            .body("areaDomainActionMapping", notNullValue())
            .body("areaDomainActionMapping.size()", greaterThan(0))
            .body("exampleCheckRequests", notNullValue())
            .body("exampleCheckRequests.size()", greaterThan(0))
            .body("didYouKnow", notNullValue())
            .body("didYouKnow.size()", greaterThan(0))
            .body("exampleCheckRequests.find { it.intent.contains('ADMIN') }.body.area", is("integration"))
            .body("exampleCheckRequests.find { it.intent.contains('ADMIN') }.body.functionalDomain", is("query"))
            .body("exampleCheckRequests.find { it.intent.contains('ADMIN') }.body.action", is("find"));
    }

    @Test
    public void query_hints_returns_grammar_examples_and_did_you_know() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/query-hints")
        .then()
            .statusCode(200)
            .body("queryGrammarSummary", notNullValue())
            .body("queryGrammarSummary.operators", notNullValue())
            .body("queryGrammarSummary.expand", notNullValue())
            .body("exampleQueries", notNullValue())
            .body("exampleQueries.size()", greaterThan(0))
            .body("didYouKnow", notNullValue())
            .body("didYouKnow.size()", greaterThan(0))
            .body("exampleQueries.find { it.intent == 'Locations in Atlanta' }.query", is("city:Atlanta"))
            .body("exampleQueries.find { it.intent == 'Locations in Atlanta' }.rootType", is("Location"));
    }

    @Test
    public void execute_unknown_tool_returns_400() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"tool\":\"unknown_tool\",\"arguments\":{}}")
        .when()
            .post("/api/agent/execute")
        .then()
            .statusCode(400);
    }
}
