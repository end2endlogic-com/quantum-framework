package com.e2eq.framework.api.agent;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.api.query.QueryGatewayResource;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST resource for agent discovery, schema, and unified execute (tools list, root types, per-type schema, execute).
 * Delegates CRUDL execution to the Query Gateway via {@link AgentExecuteHandler}.
 *
 * @see QueryGatewayResource
 * @see AgentToolsProvider
 * @see SchemaService
 * @see AgentExecuteHandler
 */
@Path("/api/agent")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@FunctionalMapping(area = "integration", domain = "query")
public class AgentResource {

    @Inject
    AgentToolsProvider agentToolsProvider;

    @Inject
    SchemaService schemaService;

    @Inject
    QueryGatewayResource queryGatewayResource;

    @Inject
    AgentExecuteHandler agentExecuteHandler;

    @Inject
    QueryHintsProvider queryHintsProvider;

    @Inject
    PermissionHintsProvider permissionHintsProvider;

    /**
     * Returns the list of gateway tools (query_rootTypes, query_plan, query_find, query_save,
     * query_delete, query_deleteMany) for discovery. Optionally scoped by realm for tenant config.
     *
     * @param realm optional realm; when tenant config is present, only enabled tools for that realm are returned
     * @return tools list and count
     */
    @GET
    @Path("/tools")
    @FunctionalAction("listTools")
    public AgentToolsProvider.AgentToolsResponse tools(@QueryParam("realm") String realm) {
        return agentToolsProvider.getTools(realm);
    }

    /**
     * Returns the list of entity types (root types) that can be used with the gateway.
     * Wraps GET /api/query/rootTypes for a single integration point.
     *
     * @return root types list and count (same shape as QueryGatewayResource.listRootTypes)
     */
    @GET
    @Path("/schema")
    @FunctionalAction("listSchema")
    public QueryGatewayResource.RootTypesResponse schema() {
        return queryGatewayResource.listRootTypes();
    }

    /**
     * Returns JSON Schema-like structure for a single root type so the agent can build valid find/save payloads.
     *
     * @param rootType simple name or FQCN (same resolution as gateway)
     * @return JSON Schema (type, title, properties)
     */
    @GET
    @Path("/schema/{rootType}")
    @FunctionalAction("getSchema")
    public Map<String, Object> schemaForRootType(@PathParam("rootType") String rootType) {
        return schemaService.getSchemaForRootType(rootType);
    }

    /**
     * Returns query grammar summary, example queries by intent, and "did you know" hints
     * so agents and developers can answer "what is the query string to retrieve X?" and
     * discover expand/ontology usage. Use with GET /api/agent/schema to build valid queries.
     *
     * @return queryGrammarSummary (operators, expand, ontologyEdges), exampleQueries (intent, query, rootType, description), didYouKnow (title, body, exampleQuery)
     */
    @GET
    @Path("/query-hints")
    @FunctionalAction("getQueryHints")
    public QueryHintsProvider.QueryHintsResponse queryHints() {
        return queryHintsProvider.getHints();
    }

    /**
     * Returns permission check/evaluate API summary, area/domain/action mapping,
     * example check requests, and "did you know" hints so agents can answer
     * "can role X perform action Y on entity Z?", "if not, why not?" (winningRuleName),
     * suggest rule changes, and generate least-privilege rules for a scenario.
     *
     * @return checkEndpoint, evaluateEndpoint, areaDomainActionMapping, exampleCheckRequests, didYouKnow
     */
    @GET
    @Path("/permission-hints")
    @FunctionalAction("getPermissionHints")
    public PermissionHintsProvider.PermissionHintsResponse permissionHints() {
        return permissionHintsProvider.getHints();
    }

    /**
     * Executes a gateway tool by name with the given arguments. Realm is resolved from
     * arguments.realm or the current principal's default realm. When tenant config has
     * runAsUserId and a resolver is present, execution runs under that user's context.
     *
     * @param request tool name, arguments (rootType, query, entity, id, realm, page, sort), optional sessionId/traceId
     * @return response from the underlying gateway operation (same shape as POST /api/query/*)
     */
    @POST
    @Path("/execute")
    @FunctionalAction("execute")
    public Response execute(ExecuteRequest request) {
        String tool = request.tool != null ? request.tool : request.name;
        Map<String, Object> arguments = request.arguments != null ? request.arguments : request.params;
        return agentExecuteHandler.execute(tool, arguments != null ? arguments : Map.of());
    }

    /** Request body for POST /api/agent/execute. */
    @RegisterForReflection
    public static class ExecuteRequest {
        /** Tool name (e.g. query_find). */
        public String tool;
        /** Alternative field name for tool. */
        public String name;
        /** Arguments for the gateway (rootType, query, entity, id, realm, page, sort). */
        public Map<String, Object> arguments;
        /** Alternative field name for arguments. */
        public Map<String, Object> params;
        /** Optional session ID for audit / multi-turn correlation. */
        public String sessionId;
        /** Optional trace ID for audit. */
        public String traceId;
    }
}
