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

    // ========================================================================
    // IMPORT / EXPORT TOOLS
    // ========================================================================

    @Tool(description = "Analyze a CSV for import into the specified entity type. Returns a session ID with "
            + "a preview of rows, their intents (INSERT/UPDATE), and any validation errors. "
            + "Use query_import_rows to paginate through all rows, and query_import_commit to save.")
    String query_import_analyze(
            @ToolArg(description = "Entity type (e.g. Location, Order). Use query_rootTypes to discover valid values.") String rootType,
            @ToolArg(description = "CSV content as a string including a header row followed by data rows") String csvContent,
            @ToolArg(description = "Comma-separated list of entity field names matching CSV columns (e.g. 'refName,displayName,status')") String columns,
            @ToolArg(description = "Optional tenant realm") String realm,
            @ToolArg(description = "Optional field separator character (default ',')") String fieldSeparator,
            @ToolArg(description = "Optional quote character (default '\"')") String quoteChar) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootType", rootType);
        args.put("csvContent", csvContent);
        if (columns != null) {
            args.put("columns", java.util.Arrays.asList(columns.split(",")));
        }
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        if (fieldSeparator != null && !fieldSeparator.isEmpty()) args.put("fieldSeparator", fieldSeparator);
        if (quoteChar != null && !quoteChar.isEmpty()) args.put("quoteChar", quoteChar);
        return executeAndSerialize("query_import_analyze", args);
    }

    @Tool(description = "Fetch analyzed CSV rows for an import session with pagination. "
            + "Useful for reviewing errors or large imports before committing.")
    String query_import_rows(
            @ToolArg(description = "Session ID returned by query_import_analyze") String sessionId,
            @ToolArg(description = "Entity type used in the original analyze call") String rootType,
            @ToolArg(description = "Optional tenant realm") String realm,
            @ToolArg(description = "Number of rows to skip (default 0)") Integer skip,
            @ToolArg(description = "Max rows to return (default 50)") Integer limit,
            @ToolArg(description = "Only return rows with errors (default false)") Boolean onlyErrors,
            @ToolArg(description = "Filter by intent: INSERT, UPDATE, or SKIP") String intent) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sessionId", sessionId);
        args.put("rootType", rootType);
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        if (skip != null) args.put("skip", skip);
        if (limit != null) args.put("limit", limit);
        if (onlyErrors != null) args.put("onlyErrors", onlyErrors);
        if (intent != null && !intent.isBlank()) args.put("intent", intent);
        return executeAndSerialize("query_import_rows", args);
    }

    @Tool(description = "Commit a previously analyzed CSV import session. Saves all valid (error-free) "
            + "INSERT and UPDATE rows to the database. Returns imported and failed counts.")
    String query_import_commit(
            @ToolArg(description = "Session ID returned by query_import_analyze") String sessionId,
            @ToolArg(description = "Entity type used in the original analyze call") String rootType,
            @ToolArg(description = "Optional tenant realm") String realm) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sessionId", sessionId);
        args.put("rootType", rootType);
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        return executeAndSerialize("query_import_commit", args);
    }

    @Tool(description = "Cancel a CSV import session, discarding all analyzed rows and session data.")
    String query_import_cancel(
            @ToolArg(description = "Session ID returned by query_import_analyze") String sessionId,
            @ToolArg(description = "Entity type used in the original analyze call") String rootType,
            @ToolArg(description = "Optional tenant realm") String realm) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sessionId", sessionId);
        args.put("rootType", rootType);
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        return executeAndSerialize("query_import_cancel", args);
    }

    @Tool(description = "Export entities matching a BIAPI query as CSV. Returns CSV inline for small "
            + "results or writes to a file for large results. Use query_count first to estimate size.")
    String query_export(
            @ToolArg(description = "Entity type (e.g. Location, Order). Use query_rootTypes to discover valid values.") String rootType,
            @ToolArg(description = "Optional BIAPI query to filter exported entities (e.g. 'status:ACTIVE')") String query,
            @ToolArg(description = "Comma-separated list of columns to include in export (e.g. 'refName,displayName,status')") String columns,
            @ToolArg(description = "Optional tenant realm") String realm,
            @ToolArg(description = "Optional max rows to export (default: all)") Integer limit,
            @ToolArg(description = "Optional rows to skip (default 0)") Integer skip) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rootType", rootType);
        if (query != null) args.put("query", query);
        if (columns != null) {
            args.put("columns", java.util.Arrays.asList(columns.split(",")));
        }
        if (realm != null && !realm.isBlank()) args.put("realm", realm);
        if (limit != null) args.put("limit", limit);
        if (skip != null) args.put("skip", skip);
        return executeAndSerialize("query_export", args);
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
