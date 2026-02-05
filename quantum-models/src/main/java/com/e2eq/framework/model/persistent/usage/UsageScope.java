package com.e2eq.framework.model.persistent.usage;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Defines which APIs, tools, or LLM configurations an allocation applies to.
 * Used by {@link TenantTokenAllocation} to scope consumption.
 * <p>
 * An empty list means "no scope" (allocation does not match). Use a single element "*" or
 * application convention for "all" within that category.
 *
 * @see TenantTokenAllocation
 */
@RegisterForReflection
public class UsageScope {

    /**
     * API identifiers in the form {@code area/domain/action} (e.g. {@code integration/query/find}).
     * When non-empty, only API calls matching one of these identifiers consume from this allocation.
     */
    private List<String> apiIdentifiers = Collections.emptyList();

    /**
     * Agent tool names (e.g. {@code query_find}, {@code query_save}). When non-empty, only
     * tool invocations matching one of these names consume from this allocation.
     */
    private List<String> toolNames = Collections.emptyList();

    /**
     * Optional LLM config keys. When non-empty, only usage for the given LLM config keys
     * consumes from this allocation. Enables different token pools per LLM configuration.
     */
    private List<String> llmConfigKeys = Collections.emptyList();

    public UsageScope() {
    }

    public UsageScope(List<String> apiIdentifiers, List<String> toolNames, List<String> llmConfigKeys) {
        this.apiIdentifiers = apiIdentifiers != null ? apiIdentifiers : Collections.emptyList();
        this.toolNames = toolNames != null ? toolNames : Collections.emptyList();
        this.llmConfigKeys = llmConfigKeys != null ? llmConfigKeys : Collections.emptyList();
    }

    public List<String> getApiIdentifiers() {
        return apiIdentifiers;
    }

    public void setApiIdentifiers(List<String> apiIdentifiers) {
        this.apiIdentifiers = apiIdentifiers != null ? apiIdentifiers : Collections.emptyList();
    }

    public List<String> getToolNames() {
        return toolNames;
    }

    public void setToolNames(List<String> toolNames) {
        this.toolNames = toolNames != null ? toolNames : Collections.emptyList();
    }

    public List<String> getLlmConfigKeys() {
        return llmConfigKeys;
    }

    public void setLlmConfigKeys(List<String> llmConfigKeys) {
        this.llmConfigKeys = llmConfigKeys != null ? llmConfigKeys : Collections.emptyList();
    }

    /**
     * Returns true if this scope matches the given API identifier (area/domain/action).
     */
    public boolean matchesApi(String area, String functionalDomain, String action) {
        if (apiIdentifiers == null || apiIdentifiers.isEmpty()) return false;
        String combined = (area != null ? area : "") + "/" + (functionalDomain != null ? functionalDomain : "") + "/" + (action != null ? action : "");
        return apiIdentifiers.stream().anyMatch(id -> "*".equals(id) || id.equals(combined));
    }

    /**
     * Returns true if this scope matches the given tool name.
     */
    public boolean matchesTool(String toolName) {
        if (toolNames == null || toolNames.isEmpty()) return false;
        return toolNames.stream().anyMatch(t -> "*".equals(t) || t.equals(toolName));
    }

    /**
     * Returns true if this scope matches the given LLM config key (or any when key is null).
     */
    public boolean matchesLlmConfig(String llmConfigKey) {
        if (llmConfigKeys == null || llmConfigKeys.isEmpty()) return llmConfigKey == null;
        if (llmConfigKey == null) return llmConfigKeys.contains("*");
        return llmConfigKeys.stream().anyMatch(k -> "*".equals(k) || k.equals(llmConfigKey));
    }
}
