package com.e2eq.framework.api.agent;

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
 * Provides the list of agent tools (six Query Gateway operations) for discovery.
 * Used by GET /api/agent/tools. Tools can be filtered by tenant enabledTools
 * when tenant config is present.
 */
@ApplicationScoped
public class AgentToolsProvider {

    private static final String AREA = "integration";
    private static final String DOMAIN = "query";

    @Inject
    TenantAgentConfigResolver tenantAgentConfigResolver;

    /**
     * Returns the list of gateway tools (query_rootTypes, query_plan, query_find,
     * query_save, query_delete, query_deleteMany) with name, description, and
     * parameters (JSON Schema-like). When realm is set and tenant config has
     * enabledTools, only those tools are returned.
     *
     * @param realm optional realm for tenant config; if null, all six tools are returned
     * @return response with tools list and count
     */
    public AgentToolsResponse getTools(String realm) {
        List<AgentToolInfo> tools = new ArrayList<>();
        tools.add(buildQueryRootTypes());
        tools.add(buildQueryPlan());
        tools.add(buildQueryFind());
        tools.add(buildQuerySave());
        tools.add(buildQueryDelete());
        tools.add(buildQueryDeleteMany());

        if (realm != null && !realm.isBlank()) {
            Optional<TenantAgentConfig> config = tenantAgentConfigResolver.resolve(realm);
            if (config.isPresent() && config.get().enabledTools != null && !config.get().enabledTools.isEmpty()) {
                Set<String> enabled = config.get().enabledTools.stream().collect(Collectors.toSet());
                tools = tools.stream().filter(t -> enabled.contains(t.name)).toList();
            }
        }

        AgentToolsResponse response = new AgentToolsResponse();
        response.tools = tools;
        response.count = tools.size();
        return response;
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
