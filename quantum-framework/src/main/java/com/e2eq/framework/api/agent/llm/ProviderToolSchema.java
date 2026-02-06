package com.e2eq.framework.api.agent.llm;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * Provider-agnostic tool schema for LLM API (name, description, input schema).
 */
@RegisterForReflection
public class ProviderToolSchema {

    private String name;
    private String description;
    private Map<String, Object> inputSchema;

    public ProviderToolSchema() {
    }

    public ProviderToolSchema(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema;
    }
}
