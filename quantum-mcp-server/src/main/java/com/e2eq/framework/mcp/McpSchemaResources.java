package com.e2eq.framework.mcp;

import com.e2eq.framework.api.agent.SchemaService;
import com.e2eq.framework.api.query.QueryGatewayResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Exposes gateway-derived schema as MCP resources so AI clients can discover
 * available entity types and their field structures.
 *
 * <p>MCP clients access these via {@code resources/list} and {@code resources/read}
 * in the JSON-RPC protocol.</p>
 *
 * <ul>
 *   <li>{@code quantum://schema} — lists all root types (entity types available for query/save/delete)</li>
 * </ul>
 *
 * <p>For per-type schema, agents should use the {@code query_rootTypes} tool to discover types,
 * then the REST endpoint {@code GET /api/agent/schema/{rootType}} for detailed field info,
 * or call {@link McpGatewayTools#query_find} with a small limit to see sample data.</p>
 *
 * @see SchemaService
 * @see QueryGatewayResource
 */
public class McpSchemaResources {

    @Inject
    QueryGatewayResource queryGatewayResource;

    @Inject
    SchemaService schemaService;

    @Inject
    ObjectMapper objectMapper;

    @Resource(uri = "quantum://schema", description = "List all available entity types (root types) with class name, simple name, and collection name")
    ResourceResponse listSchema() {
        try {
            QueryGatewayResource.RootTypesResponse rootTypes = queryGatewayResource.listRootTypes();
            String json = objectMapper.writeValueAsString(rootTypes);
            return new ResourceResponse(List.of(
                    TextResourceContents.create("quantum://schema", json)));
        } catch (Exception e) {
            return new ResourceResponse(List.of(
                    TextResourceContents.create("quantum://schema",
                            "{\"error\":\"" + e.getMessage() + "\"}")));
        }
    }
}
