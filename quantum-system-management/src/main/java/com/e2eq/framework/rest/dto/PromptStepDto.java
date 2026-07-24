package com.e2eq.framework.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * DTO for one prompt step in an agent's context.
 */
@RegisterForReflection
public class PromptStepDto {

    /** Display order (1-based). Lower runs first. */
    public int order;

    /** Role: "system" or "user". */
    public String role = "system";

    /** Prompt content. */
    public String content;

    public PromptStepDto() {
    }

    public PromptStepDto(int order, String role, String content) {
        this.order = order;
        this.role = role != null ? role : "system";
        this.content = content;
    }
}
