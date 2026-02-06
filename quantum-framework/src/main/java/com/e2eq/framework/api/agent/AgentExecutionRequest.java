package com.e2eq.framework.api.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * Request for agent execution (observe-think-act loop or continue conversation).
 */
@RegisterForReflection
public class AgentExecutionRequest {

    /** Which agent (refName) to use. */
    private String agentRef;
    /** User message. */
    private String userMessage;
    /** Existing conversation id (null = new conversation). */
    private String conversationId;
    /** Additional context variables. */
    private Map<String, Object> context;
    /** Target realm (defaults to current). */
    private String realmId;
    /** Acting principal (defaults to current user). */
    private String principalId;

    public String getAgentRef() {
        return agentRef;
    }

    public void setAgentRef(String agentRef) {
        this.agentRef = agentRef;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public String getRealmId() {
        return realmId;
    }

    public void setRealmId(String realmId) {
        this.realmId = realmId;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }
}
