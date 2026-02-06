package com.e2eq.framework.api.agent.llm;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.List;

/**
 * Stub LLM client that returns a fixed message. Use when no real LLM is configured;
 * allows the execution loop to be exercised and conversation persistence to work.
 */
@ApplicationScoped
public class StubLlmClient implements LLMClient {

    private static final String STUB_MESSAGE = "LLM not configured. Connect an LLM provider (e.g. Anthropic, OpenAI) to enable agent execution.";

    @Override
    public LlmChatResponse chat(List<LlmMessage> messages, List<ProviderToolSchema> tools) {
        LlmChatResponse response = new LlmChatResponse();
        response.setText(STUB_MESSAGE);
        response.setToolCalls(Collections.emptyList());
        response.setStopReason("end_turn");
        return response;
    }
}
