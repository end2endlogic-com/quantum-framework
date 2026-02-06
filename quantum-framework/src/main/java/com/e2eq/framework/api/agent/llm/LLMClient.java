package com.e2eq.framework.api.agent.llm;

import java.util.List;

/**
 * Abstraction for calling an LLM with messages and optional tools.
 * Implementations: stub (no-op), Anthropic, OpenAI, etc.
 */
public interface LLMClient {

    /**
     * Sends messages and optional tools to the LLM and returns the response (text and/or tool calls).
     *
     * @param messages conversation messages (system, user, assistant, tool)
     * @param tools     optional tool schemas for tool use
     * @return response with text and/or tool calls
     */
    LlmChatResponse chat(List<LlmMessage> messages, List<ProviderToolSchema> tools);
}
