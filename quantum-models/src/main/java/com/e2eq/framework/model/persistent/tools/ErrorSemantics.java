package com.e2eq.framework.model.persistent.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Semantics for an error (e.g. HTTP status): retry, clarify, fail, or fallback to another tool.
 * Used by {@link ToolDefinition} for error handling configuration.
 */
@RegisterForReflection
public class ErrorSemantics {

    /** Action: "retry", "clarify", "fail", "fallback". */
    private String action;
    /** Human-readable error description for LLM. */
    private String message;
    /** Alternative tool to try on this error. */
    private String fallbackToolRef;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFallbackToolRef() {
        return fallbackToolRef;
    }

    public void setFallbackToolRef(String fallbackToolRef) {
        this.fallbackToolRef = fallbackToolRef;
    }
}
