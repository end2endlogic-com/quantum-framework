package com.e2eq.framework.mcp;

import com.e2eq.framework.api.agent.AgentExecuteHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes the six Query Gateway operations as native MCP tools via the Quarkus MCP Server extension.
 *
 * <p>Each {@code @Tool} method maps 1:1 to a gateway tool and delegates to
 * {@link AgentExecuteHandler}, reusing the same security, realm resolution,
 * and query execution as the REST {@code /api/agent/execute} endpoint.</p>
 *
 * <p>MCP clients (Cursor, Claude Desktop, ChatGPT, etc.) discover these tools
 * automatically via the MCP {@code tools/list} JSON-RPC method; tool invocations
 * arrive via {@code tools/call} over Streamable HTTP ({@code /mcp}) or SSE.</p>
 *
 * @see AgentExecuteHandler
 * @see com.e2eq.framework.api.query.QueryGatewayResource
 */
public class McpGatewayTools {

    @Inject
    AgentExecuteHandler agentExecuteHandler;

    @Inject
    ObjectMapper objectMapper;

    @Tool(description = "List available entity types (root types) that can be queried, saved, or deleted via the gateway")
    String query_rootTypes() {
        return executeAndSerialize("query_rootTypes", Map.of());
    }

    @Tool(description = "Return the query execution plan (FILTER vs AGGREGATION mode) for a rootType and BIAPI query string. "
            + "Use this before query_find to verify expand() paths and execution mode.")
    String query_plan(
            @ToolArg(description = "Entity type simple name or FQCN (e.g. Order, Location, CodeList). Use query_rootTypes to discover valid values.") String rootType,
            @ToolArg(description = "BIAPI query string (e.g. 'status:ACTIVE', 'expand(customer) && status:ACTIVE')") String query) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootType", rootType);
        args.put("query", query);
        return executeAndSerialize("query_plan", args);
    }

    @Tool(description = "Execute a BIAPI query and return matching entities. "
            + "Supports field matching (status:ACTIVE), wildcards (name:*Warehouse*), comparisons (age>#21), "
            + "logical operators (&& || !!), and expand(path) for relationship hydration.")
    String query_find(
            @ToolArg(description = "Entity type (e.g. Order, Location). Use query_rootTypes to discover valid values.") String rootType,
            @ToolArg(description = "BIAPI query string (e.g. 'refName:LOC-001', 'status:ACTIVE && region:West')") String query,
            @ToolArg(description = "Optional tenant realm. When omitted, the caller's default realm is used.") String realm,
            @ToolArg(description = "Optional max number of results to return (default 50)") Integer limit,
            @ToolArg(description = "Optional number of results to skip for pagination (default 0)") Integer skip) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootType", rootType);
        if (query != null) args.put("query", query);
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        if (limit != null || skip != null) {
            Map<String, Object> page = new LinkedHashMap<>();
            if (limit != null) page.put("limit", limit);
            if (skip != null) page.put("skip", skip);
            args.put("page", page);
        }
        return executeAndSerialize("query_find", args);
    }

    @Tool(description = "Count entities matching a BIAPI query. Returns only the count, not the entities themselves. "
            + "More efficient than query_find when you only need the number of matching records.")
    String query_count(
            @ToolArg(description = "Entity type (e.g. Order, Location). Use query_rootTypes to discover valid values.") String rootType,
            @ToolArg(description = "Optional BIAPI query string to filter which entities to count (e.g. 'status:ACTIVE'). If omitted, counts all entities of this type.") String query,
            @ToolArg(description = "Optional tenant realm. When omitted, the caller's default realm is used.") String realm) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootType", rootType);
        if (query != null) args.put("query", query);
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        return executeAndSerialize("query_count", args);
    }

    @Tool(description = "Save (insert or update) an entity. If the entity has an _id it will be updated; otherwise a new entity is inserted.")
    String query_save(
            @ToolArg(description = "Entity type (e.g. Location, Order)") String rootType,
            @ToolArg(description = "Entity data as a JSON object (field names must match the schema for this rootType)") String entity,
            @ToolArg(description = "Optional tenant realm") String realm) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootType", rootType);
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> entityMap = objectMapper.readValue(entity, Map.class);
            args.put("entity", entityMap);
        } catch (Exception e) {
            return "{\"error\":\"InvalidEntity\",\"message\":\"Failed to parse entity JSON: " + e.getMessage() + "\"}";
        }
        return executeAndSerialize("query_save", args);
    }

    @Tool(description = "Delete a single entity by its ObjectId")
    String query_delete(
            @ToolArg(description = "Entity type (e.g. Location, Order)") String rootType,
            @ToolArg(description = "ObjectId hex string of the entity to delete") String id,
            @ToolArg(description = "Optional tenant realm") String realm) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootType", rootType);
        args.put("id", id);
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        return executeAndSerialize("query_delete", args);
    }

    @Tool(description = "Delete multiple entities matching a BIAPI query. Cannot use expand() in delete queries.")
    String query_deleteMany(
            @ToolArg(description = "Entity type (e.g. Location, Order)") String rootType,
            @ToolArg(description = "BIAPI query string to match entities for deletion (e.g. 'status:INACTIVE')") String query,
            @ToolArg(description = "Optional tenant realm") String realm) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootType", rootType);
        if (query != null) args.put("query", query);
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        return executeAndSerialize("query_deleteMany", args);
    }

    /**
     * Delegates to {@link AgentExecuteHandler} and serializes the JAX-RS Response entity to JSON.
     */
    private String executeAndSerialize(String tool, Map<String, Object> arguments) {
        try {
            Response response = agentExecuteHandler.execute(tool, arguments);
            Object entity = response.getEntity();
            if (entity == null) {
                return "{\"status\":" + response.getStatus() + "}";
            }
            return objectMapper.writeValueAsString(entity);
        } catch (Exception e) {
            return "{\"error\":\"ExecutionFailed\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
