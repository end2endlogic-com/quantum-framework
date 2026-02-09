package com.e2eq.framework.model.persistent.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * Example tool invocation for LLM few-shot. Used by {@link ToolDefinition}.
 */
@RegisterForReflection
public class ToolExample {

    private String scenario;
    private Map<String, Object> input;
    private Map<String, Object> output;

    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }
    public Map<String, Object> getInput() { return input; }
    public void setInput(Map<String, Object> input) { this.input = input; }
    public Map<String, Object> getOutput() { return output; }
    public void setOutput(Map<String, Object> output) { this.output = output; }
}
