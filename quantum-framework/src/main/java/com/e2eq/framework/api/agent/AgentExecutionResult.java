package com.e2eq.framework.api.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result of agent execution: conversation id, response text, tool invocations, status.
 */
@RegisterForReflection
public class AgentExecutionResult {

    private String conversationId;
    private String responseText;
    private List<ToolInvocationResult> toolCalls = new ArrayList<>();
    private int iterationsUsed;
    private int tokensUsed;
    /** COMPLETED, MAX_ITERATIONS, TIMEOUT, ERROR, AWAITING_APPROVAL */
    private String status = "COMPLETED";
    private Map<String, Object> outputContext;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getResponseText() {
        return responseText;
    }

    public void setResponseText(String responseText) {
        this.responseText = responseText;
    }

    public List<ToolInvocationResult> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolInvocationResult> toolCalls) {
        this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
    }

    public int getIterationsUsed() {
        return iterationsUsed;
    }

    public void setIterationsUsed(int iterationsUsed) {
        this.iterationsUsed = iterationsUsed;
    }

    public int getTokensUsed() {
        return tokensUsed;
    }

    public void setTokensUsed(int tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getOutputContext() {
        return outputContext;
    }

    public void setOutputContext(Map<String, Object> outputContext) {
        this.outputContext = outputContext;
    }

    @RegisterForReflection
    public static class ToolInvocationResult {
        private String toolRef;
        private Map<String, Object> input;
        private Map<String, Object> output;
        private long durationMs;
        private String status;
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
}
