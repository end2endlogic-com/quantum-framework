package com.e2eq.framework.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * Response DTO for an Agent (GET list, GET by id).
 */
@RegisterForReflection
public class AgentResponse {

    public String id;
    public String refName;
    public String name;
    public String description;
    public String llmConfigRef;
    public List<PromptStepDto> context = new ArrayList<>();
    public List<String> toolRefs;
    public List<String> toolCategories;
    public List<String> toolTags;
    public List<String> excludedToolRefs;
    public int maxToolsInContext;
    public List<String> delegateAgentRefs;
    public String responseFormat;
    public String securityUri;
    public String principalRef;
    public List<String> allowedRealms;
    public boolean requiresApproval;
    public Boolean enabled;
    public List<String> enabledTools;

    public AgentResponse() {
    }
}
