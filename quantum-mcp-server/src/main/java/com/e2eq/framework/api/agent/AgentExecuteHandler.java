package com.e2eq.framework.api.agent;

import com.e2eq.framework.api.query.QueryGatewayResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Executes gateway tools by name with the given arguments. Delegates to QueryGatewayResource.
 * Realm is resolved from arguments.realm or the current principal's default realm.
 *
 * @see AgentResource
 * @see QueryGatewayResource
 * @see TenantAgentConfigResolver
 */
@ApplicationScoped
public class AgentExecuteHandler {

    @Inject
    QueryGatewayResource queryGatewayResource;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Executes a gateway tool by name.
     *
     * @param tool      tool name (e.g. query_find, query_rootTypes)
     * @param arguments map of arguments (rootType, query, realm, page, sort, entity, id, etc.)
     * @return response from the underlying gateway operation
     */
    public Response execute(String tool, Map<String, Object> arguments) {
        if (tool == null || tool.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "ToolRequired", "message", "tool is required"))
                    .build();
        }
        arguments = arguments != null ? arguments : Map.of();

        return switch (tool) {
            case "query_rootTypes" -> {
                QueryGatewayResource.RootTypesResponse r = queryGatewayResource.listRootTypes();
                yield Response.ok(r).build();
            }
            case "query_plan" -> {
                QueryGatewayResource.PlanRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.PlanRequest.class);
                QueryGatewayResource.PlanResponse r = queryGatewayResource.plan(req);
                yield Response.ok(r).build();
            }
            case "query_find" -> {
                QueryGatewayResource.FindRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.FindRequest.class);
                yield queryGatewayResource.find(req);
            }
            case "query_save" -> {
                QueryGatewayResource.SaveRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.SaveRequest.class);
                yield queryGatewayResource.save(req);
            }
            case "query_delete" -> {
                QueryGatewayResource.DeleteRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.DeleteRequest.class);
                yield queryGatewayResource.delete(req);
            }
            case "query_deleteMany" -> {
                QueryGatewayResource.DeleteManyRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.DeleteManyRequest.class);
                yield queryGatewayResource.deleteMany(req);
            }
            case "query_count" -> {
                QueryGatewayResource.CountRequest req = objectMapper.convertValue(arguments, QueryGatewayResource.CountRequest.class);
                yield queryGatewayResource.count(req);
            }
            default -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "UnknownTool", "message", "Unknown tool: " + tool,
                            "availableTools", new String[]{"query_rootTypes", "query_plan", "query_find", "query_count", "query_save", "query_delete", "query_deleteMany"}))
                    .build();
        };
    }
}
