package com.e2eq.framework.api.agent;

import com.e2eq.framework.model.persistent.base.CodeList;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test that simulates the <strong>MCP bridge flow</strong> end-to-end.
 *
 * <p>This test documents the exact HTTP sequence an MCP bridge must perform when
 * integrating with the Quantum agent APIs. It serves as both a regression test
 * and a living example for bridge implementers. See the user guide
 * (ai-agent-integration.adoc) section "MCP Bridge Configuration" for env vars
 * and endpoint contracts.
 *
 * <p>Flow:
 * <ol>
 *   <li>Discover tools (MCP tools/list) → GET /api/agent/tools</li>
 *   <li>List schema (MCP resources/list) → GET /api/agent/schema</li>
 *   <li>Read schema for one type (MCP resources/read) → GET /api/agent/schema/{rootType}</li>
 *   <li>Execute query_rootTypes (MCP tools/call) → POST /api/agent/execute</li>
 *   <li>Execute query_plan (MCP tools/call) → POST /api/agent/execute</li>
 *   <li>Execute query_find (MCP tools/call) → POST /api/agent/execute</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("MCP Bridge flow integration test")
public class AgentMcpBridgeFlowIT {

    @Test
    @DisplayName("Full MCP bridge flow: discover tools, schema, then execute query_rootTypes, query_plan, query_find")
    public void mcp_bridge_flow_discovers_tools_schema_and_executes_gateway_operations() {
        // ---- 1. MCP tools/list: discover tools ----
        given()
            .accept(ContentType.JSON)
            .queryParam("realm", "defaultRealm")
        .when()
            .get("/api/agent/tools")
        .then()
            .statusCode(200)
            .body("tools", notNullValue())
            .body("count", greaterThanOrEqualTo(1))
            .body("tools.name", hasItems(
                "query_rootTypes", "query_plan", "query_find", "query_save", "query_delete", "query_deleteMany"));

        // ---- 2. MCP resources/list: list entity types (schema) ----
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/schema")
        .then()
            .statusCode(200)
            .body("rootTypes", notNullValue())
            .body("count", greaterThan(0))
            .body("rootTypes.className", hasItem(CodeList.class.getName()));

        // ---- 3. MCP resources/read: read schema for one root type ----
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/agent/schema/CodeList")
        .then()
            .statusCode(200)
            .body("type", is("object"))
            .body("title", is("CodeList"))
            .body("properties", notNullValue());

        // ---- 4. MCP tools/call: execute query_rootTypes ----
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "tool": "query_rootTypes",
                  "arguments": {},
                  "sessionId": "mcp-test-session",
                  "traceId": "mcp-test-trace"
                }
                """)
        .when()
            .post("/api/agent/execute")
        .then()
            .statusCode(200)
            .body("rootTypes", notNullValue())
            .body("count", greaterThan(0));

        // ---- 5. MCP tools/call: execute query_plan ----
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "tool": "query_plan",
                  "arguments": {
                    "rootType": "CodeList",
                    "query": "refName:ACTIVE"
                  }
                }
                """)
        .when()
            .post("/api/agent/execute")
        .then()
            .statusCode(200)
            .body("mode", notNullValue())
            .body("expandPaths", notNullValue());

        // ---- 6. MCP tools/call: execute query_find (with realm and page) ----
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "tool": "query_find",
                  "arguments": {
                    "rootType": "CodeList",
                    "query": "refName:ACTIVE",
                    "realm": "defaultRealm",
                    "page": { "limit": 5, "skip": 0 }
                  }
                }
                """)
        .when()
            .post("/api/agent/execute")
        .then()
            .statusCode(200)
            .body("rows", notNullValue())
            .body("limit", is(5))
            .body("offset", is(0));
    }
}
