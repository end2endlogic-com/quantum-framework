package com.e2eq.framework.model.persistent.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * Record of a single tool invocation within a conversation turn.
 * Stored embedded in {@link AgentConversationTurn}; not a separate collection.
 */
@RegisterForReflection
public class ToolInvocationRecord {

    private String toolRef;
    private Map<String, Object> input;
    private Map<String, Object> output;
    private long durationMs;
    private String status;  // SUCCESS, ERROR, SKIPPED, PERMISSION_DENIED
    private String errorMessage;

    public String getToolRef() {
        return toolRef;
    }

    public void setToolRef(String toolRef) {
        this.toolRef = toolRef;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
