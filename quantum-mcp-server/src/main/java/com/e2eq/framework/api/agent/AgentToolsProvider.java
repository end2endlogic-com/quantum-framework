package com.e2eq.framework.api.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides the list of gateway tools for agent discovery. Optionally filters by tenant config.
 *
 * @see AgentResource
 * @see TenantAgentConfigResolver
 */
@ApplicationScoped
public class AgentToolsProvider {

    private static final String AREA = "integration";
    private static final String DOMAIN = "query";

    private static final List<Map<String, Object>> ALL_TOOLS = List.of(
            tool("query_rootTypes", "List available entity types (root types) that can be used with the gateway",
                    List.of()),
            tool("query_plan", "Returns query execution plan (FILTER vs AGGREGATION mode) for a rootType and query",
                    List.of(param("rootType", "string", "Entity type simple or FQCN"), param("query", "string", "BIAPI query string"))),
            tool("query_find", "Execute a query and return matching entities",
                    List.of(param("rootType", "string", "Entity type"), param("query", "string", "BIAPI query"), param("realm", "string", "Optional realm"), param("page", "object", "Optional { limit, skip }"), param("sort", "array", "Optional sort specs"))),
            tool("query_save", "Save (insert or update) an entity",
                    List.of(param("rootType", "string", "Entity type"), param("realm", "string", "Optional realm"), param("entity", "object", "Entity data"))),
            tool("query_delete", "Delete an entity by ID",
                    List.of(param("rootType", "string", "Entity type"), param("realm", "string", "Optional realm"), param("id", "string", "ObjectId hex string"))),
            tool("query_deleteMany", "Delete multiple entities matching a query",
                    List.of(param("rootType", "string", "Entity type"), param("realm", "string", "Optional realm"), param("query", "string", "BIAPI query")))
    );

    @Inject
    TenantAgentConfigResolver tenantConfigResolver;

    /**
     * Returns the list of gateway tools for discovery. When realm is provided and tenant config
     * has enabledTools, returns only those tools; otherwise returns all six gateway tools.
     *
     * @param realm optional realm for tenant-specific filtering
     * @return tools list and count
     */
    public AgentToolsResponse getTools(String realm) {
        List<Map<String, Object>> tools = new ArrayList<>(ALL_TOOLS);
        if (realm != null && !realm.isBlank()) {
            Optional<TenantAgentConfig> config = tenantConfigResolver.resolve(realm);
            if (config.isPresent() && config.get().enabledTools != null && !config.get().enabledTools.isEmpty()) {
                List<String> enabled = config.get().enabledTools;
                tools = tools.stream()
                        .filter(t -> enabled.contains(t.get("name")))
                        .toList();
            }
        }
        AgentToolsResponse response = new AgentToolsResponse();
        response.tools = tools;
        response.count = tools.size();
        return response;
    }

    private static Map<String, Object> tool(String name, String description, List<Map<String, String>> parameters) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("parameters", parameters);
        m.put("area", AREA);
        m.put("domain", DOMAIN);
        return m;
    }

    private static Map<String, String> param(String name, String type, String description) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    /** Response for GET /api/agent/tools */
    @RegisterForReflection
    public static class AgentToolsResponse {
        public List<Map<String, Object>> tools;
        public int count;
    }
}
