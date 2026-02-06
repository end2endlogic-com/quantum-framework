package com.e2eq.framework.api.agent.llm;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Response from an LLM chat call: text and/or tool calls.
 */
@RegisterForReflection
public class LlmChatResponse {

    private String text;
    private List<LlmToolCall> toolCalls = Collections.emptyList();
    private String stopReason;  // end_turn, tool_use, max_tokens, etc.
    private Integer inputTokens;
    private Integer outputTokens;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<LlmToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<LlmToolCall> toolCalls) {
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }
}
