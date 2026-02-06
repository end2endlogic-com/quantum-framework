package com.e2eq.framework.api.agent.llm;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * A tool call requested by the LLM (id, name, arguments).
 */
@RegisterForReflection
public class LlmToolCall {

    private String id;
    private String name;
    private Map<String, Object> arguments;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments;
    }
}
