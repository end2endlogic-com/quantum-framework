package com.e2eq.framework.api.agent;

import com.e2eq.framework.model.persistent.agent.Agent;
import com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the set of tools available to an agent for a given realm.
 * Applies explicit refs, category/tag inclusion, exclusions, enabled filter,
 * and optional max-tools-in-context limit. Delegate agents are not yet
 * included (Phase 3+); security filtering is deferred until a security
 * service is integrated.
 */
@ApplicationScoped
public class ToolResolver {

    @Inject
    ToolDefinitionRepo toolDefinitionRepo;

    /**
     * Resolves tools for the given agent in the given realm.
     * Order: explicit refs first, then category-based, then tag-based;
     * then exclusions and enabled filter are applied; finally the list
     * is trimmed to {@link Agent#getMaxToolsInContext()} if set.
     *
     * @param agent   agent definition (toolRefs, toolCategories, toolTags, etc.)
     * @param realmId realm to resolve tools in
     * @return ordered list of enabled tools the agent can use
     */
    public List<ToolDefinition> resolveToolsForAgent(Agent agent, String realmId) {
        if (agent == null || realmId == null || realmId.isBlank()) {
            return List.of();
        }

        Set<ToolDefinition> tools = new LinkedHashSet<>();

        // 1. Explicit tool refs, or fallback to legacy enabledTools
        List<String> refs = agent.getToolRefs();
        if (refs != null && !refs.isEmpty()) {
            tools.addAll(toolDefinitionRepo.findByRefNames(realmId, refs));
        }
        if (tools.isEmpty() && (agent.getToolCategories() == null || agent.getToolCategories().isEmpty())
            && (agent.getToolTags() == null || agent.getToolTags().isEmpty())) {
            List<String> legacy = agent.getEnabledTools();
            if (legacy != null && !legacy.isEmpty()) {
                tools.addAll(toolDefinitionRepo.findByRefNames(realmId, legacy));
            }
        }

        // 2. Category-based inclusion
        List<String> categories = agent.getToolCategories();
        if (categories != null && !categories.isEmpty()) {
            tools.addAll(toolDefinitionRepo.findByCategoryIn(realmId, categories));
        }

        // 3. Tag-based inclusion
        List<String> tags = agent.getToolTags();
        if (tags != null && !tags.isEmpty()) {
            tools.addAll(toolDefinitionRepo.findByTagsAny(realmId, tags));
        }

        // 4. Remove excluded tools
        List<String> excluded = agent.getExcludedToolRefs();
        if (excluded != null && !excluded.isEmpty()) {
            tools.removeIf(t -> t.getRefName() != null && excluded.contains(t.getRefName()));
        }

        // 5. Filter by realm access and permissions (deferred: no security service yet)

        // 6. Filter by enabled status
        tools.removeIf(t -> !t.isEnabled());

        // 7. Delegate agents as tools (Phase 3+): not implemented yet

        // 8. Trim to context window budget if needed
        List<ToolDefinition> result = new ArrayList<>(tools);
        int max = agent.getMaxToolsInContext();
        if (max > 0 && result.size() > max) {
            result = result.stream().limit(max).collect(Collectors.toList());
        }

        return result;
    }
}
