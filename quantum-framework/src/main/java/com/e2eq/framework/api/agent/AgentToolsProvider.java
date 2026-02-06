package com.e2eq.framework.api.agent;

import com.e2eq.framework.api.tools.GatewayToolSeeder;
import com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Provides the list of agent tools for discovery. When realm is set, tools are
 * read from the {@link ToolDefinition} registry (after seeding gateway tools).
 * Used by GET /api/agent/tools. Tools can be filtered by tenant enabledTools.
 */
@ApplicationScoped
public class AgentToolsProvider {

    private static final String AREA = "integration";
    private static final String DOMAIN = "query";

    @Inject
    TenantAgentConfigResolver tenantAgentConfigResolver;

    @Inject
    ToolDefinitionRepo toolDefinitionRepo;

    @Inject
    GatewayToolSeeder gatewayToolSeeder;

    /**
     * Returns the list of tools. When realm is set, delegates to the tool registry
     * (seeds gateway tools if needed) and filters by tenant enabledTools. When realm
     * is null, returns the six static gateway tools for backward compatibility.
     *
     * @param realm optional realm; when set, tools come from registry and tenant filter applies
     * @return response with tools list and count
     */
    public AgentToolsResponse getTools(String realm) {
        List<AgentToolInfo> tools;
        if (realm != null && !realm.isBlank()) {
            gatewayToolSeeder.seedRealm(realm);
            List<ToolDefinition> defs = toolDefinitionRepo.list(realm);
            tools = defs.stream()
                .filter(ToolDefinition::isEnabled)
                .map(this::toAgentToolInfo)
                .toList();

            Optional<TenantAgentConfig> config = tenantAgentConfigResolver.resolve(realm);
            if (config.isPresent() && config.get().enabledTools != null && !config.get().enabledTools.isEmpty()) {
                Set<String> enabled = config.get().enabledTools.stream().collect(Collectors.toSet());
                tools = tools.stream().filter(t -> enabled.contains(t.name)).toList();
            }
        } else {
            tools = new ArrayList<>(List.of(
                buildQueryRootTypes(), buildQueryPlan(), buildQueryFind(),
                buildQuerySave(), buildQueryDelete(), buildQueryDeleteMany()));
        }

        AgentToolsResponse response = new AgentToolsResponse();
        response.tools = tools;
        response.count = tools.size();
        return response;
    }

    private AgentToolInfo toAgentToolInfo(ToolDefinition def) {
        AgentToolInfo t = new AgentToolInfo();
        t.name = def.getRefName();
        t.description = def.getDescription() != null ? def.getDescription() : def.getName();
        t.parameters = parametersForGatewayRefName(def.getRefName());
        t.area = AREA;
        t.domain = DOMAIN;
        t.action = actionForRefName(def.getRefName());
        return t;
    }

    private static String actionForRefName(String refName) {
        if (refName == null) return "EXECUTE";
        return switch (refName) {
            case "query_rootTypes" -> "listRootTypes";
            case "query_plan" -> "plan";
            case "query_find" -> "find";
            case "query_save" -> "save";
            case "query_delete" -> "delete";
            case "query_deleteMany" -> "deleteMany";
            default -> "EXECUTE";
        };
    }

    private static Map<String, Object> parametersForGatewayRefName(String refName) {
        if (refName == null) return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        return switch (refName) {
            case "query_rootTypes" -> Map.of("type", "object", "properties", Map.of(), "required", List.of());
            case "query_plan" -> buildQueryPlan().parameters;
            case "query_find" -> buildQueryFind().parameters;
            case "query_save" -> buildQuerySave().parameters;
            case "query_delete" -> buildQueryDelete().parameters;
            case "query_deleteMany" -> buildQueryDeleteMany().parameters;
            default -> Map.of("type", "object", "properties", Map.of(), "required", List.of());
        };
    }

    private static AgentToolInfo buildQueryRootTypes() {
        AgentToolInfo t = new AgentToolInfo();
        t.name = "query_rootTypes";
        t.description = "List all available entity types (rootTypes) that can be used with find, save, delete. Returns class name, simple name, and collection name.";
        t.parameters = Map.of("type", "object", "properties", Map.of(), "required", List.of());
        t.area = AREA;
        t.domain = DOMAIN;
        t.action = "listRootTypes";
        return t;
    }

    private static AgentToolInfo buildQueryPlan() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of(
                "rootType", Map.of("type", "string", "description", "Entity type (e.g. Location, Order)"),
                "query", Map.of("type", "string", "description", "BIAPI query string")
        ));
        params.put("required", List.of("rootType", "query"));
        AgentToolInfo t = new AgentToolInfo();
        t.name = "query_plan";
        t.description = "Get the execution plan for a query (FILTER vs AGGREGATION mode and expand paths). Use before find to understand how a query will run.";
        t.parameters = params;
        t.area = AREA;
        t.domain = DOMAIN;
        t.action = "plan";
        return t;
    }

    private static AgentToolInfo buildQueryFind() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("rootType", Map.of("type", "string", "description", "Entity type"));
        props.put("query", Map.of("type", "string", "description", "BIAPI query string"));
        props.put("page", Map.of("type", "object", "properties", Map.of("limit", Map.of("type", "integer"), "skip", Map.of("type", "integer"))));
        props.put("sort", Map.of("type", "array", "items", Map.of("type", "object", "properties", Map.of("field", Map.of("type", "string"), "dir", Map.of("type", "string", "enum", List.of("ASC", "DESC"))))));
        props.put("realm", Map.of("type", "string", "description", "Optional tenant realm"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("rootType"));
        AgentToolInfo t = new AgentToolInfo();
        t.name = "query_find";
        t.description = "Find entities matching a query. Supports paging, sort, and optional realm.";
        t.parameters = params;
        t.area = AREA;
        t.domain = DOMAIN;
        t.action = "find";
        return t;
    }

    private static AgentToolInfo buildQuerySave() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of(
                "rootType", Map.of("type", "string"),
                "entity", Map.of("type", "object", "description", "Entity data"),
                "realm", Map.of("type", "string")
        ));
        params.put("required", List.of("rootType", "entity"));
        AgentToolInfo t = new AgentToolInfo();
        t.name = "query_save";
        t.description = "Create or update an entity. Include entity fields as JSON; id or refName for updates.";
        t.parameters = params;
        t.area = AREA;
        t.domain = DOMAIN;
        t.action = "save";
        return t;
    }

    private static AgentToolInfo buildQueryDelete() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of(
                "rootType", Map.of("type", "string"),
                "id", Map.of("type", "string", "description", "ObjectId hex string"),
                "realm", Map.of("type", "string")
        ));
        params.put("required", List.of("rootType", "id"));
        AgentToolInfo t = new AgentToolInfo();
        t.name = "query_delete";
        t.description = "Delete one entity by ID (ObjectId hex string).";
        t.parameters = params;
        t.area = AREA;
        t.domain = DOMAIN;
        t.action = "delete";
        return t;
    }

    private static AgentToolInfo buildQueryDeleteMany() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of(
                "rootType", Map.of("type", "string"),
                "query", Map.of("type", "string"),
                "realm", Map.of("type", "string")
        ));
        params.put("required", List.of("rootType", "query"));
        AgentToolInfo t = new AgentToolInfo();
        t.name = "query_deleteMany";
        t.description = "Delete all entities matching a query. Use with care.";
        t.parameters = params;
        t.area = AREA;
        t.domain = DOMAIN;
        t.action = "deleteMany";
        return t;
    }

    @RegisterForReflection
    public static class AgentToolsResponse {
        /** List of tool definitions. */
        public List<AgentToolInfo> tools;
        /** Number of tools. */
        public int count;
    }

    @RegisterForReflection
    public static class AgentToolInfo {
        /** Tool name (e.g. query_find). */
        public String name;
        /** Short description for the LLM. */
        public String description;
        /** JSON Schema-like parameters (type, properties, required). */
        public Map<String, Object> parameters;
        /** Functional area (integration). */
        public String area;
        /** Functional domain (query). */
        public String domain;
        /** Action name (e.g. find). */
        public String action;
    }
}
