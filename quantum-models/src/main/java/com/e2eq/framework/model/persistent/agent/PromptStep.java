package com.e2eq.framework.model.persistent.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One step in an agent's context: a prompt with order, role, and content.
 * Sent in sequence to the LLM to set context before user input.
 * Stored embedded in Agent (no separate collection).
 */
@RegisterForReflection
public class PromptStep {

    /** Display order (1-based). Lower runs first. */
    private int order;

    /** Role: "system" or "user". Typically system for context. */
    private String role = "system";

    /** Prompt content. May support placeholders (e.g. {{entityId}}) substituted at invoke time. */
    private String content;

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
