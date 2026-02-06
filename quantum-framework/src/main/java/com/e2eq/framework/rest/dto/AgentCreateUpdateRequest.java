package com.e2eq.framework.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for creating or updating an Agent (POST/PUT).
 */
@RegisterForReflection
public class AgentCreateUpdateRequest {

    /** Unique ref within realm (e.g. OBLIGATIONS_SUGGEST). Required on create. */
    public String refName;

    /** Display name. */
    public String name;

    /** Optional description. */
    public String description;

    /** LLM config ref (e.g. LLM_PROVIDER_CONFIG). When null, use realm default. */
    public String llmConfigRef;

    /** Ordered prompt steps (context). */
    public List<PromptStepDto> context = new ArrayList<>();

    /** Explicit tool refs. When null/empty with no categories/tags, enabledTools is used. */
    public List<String> toolRefs;
    /** Include tools in these categories. */
    public List<String> toolCategories;
    /** Include tools with any of these tags. */
    public List<String> toolTags;
    /** Exclude these tool refs. */
    public List<String> excludedToolRefs;
    /** Max tools in context (0 = no limit). */
    public int maxToolsInContext;
    /** Delegate agent refs (Phase 3+). */
    public List<String> delegateAgentRefs;
    /** Output format hint: text, json, structured. */
    public String responseFormat;
    /** Permission URI. */
    public String securityUri;
    /** Service account ref. */
    public String principalRef;
    /** Allowed realms (empty = current only). */
    public List<String> allowedRealms;
    /** Require approval for side-effect tools. */
    public boolean requiresApproval;
    /** Whether agent is enabled. */
    public Boolean enabled;

    /** Legacy: tool names this agent can use when toolRefs/categories/tags unset. */
    public List<String> enabledTools;

    public AgentCreateUpdateRequest() {
    }
}
