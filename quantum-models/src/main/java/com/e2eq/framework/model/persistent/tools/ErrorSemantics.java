package com.e2eq.framework.model.persistent.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Error handling semantics (retry/clarify/fail) per HTTP status. Used by {@link ToolDefinition}.
 */
@RegisterForReflection
public class ErrorSemantics {

    private String action;
    private String message;
    private String fallbackToolRef;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getFallbackToolRef() { return fallbackToolRef; }
    public void setFallbackToolRef(String fallbackToolRef) { this.fallbackToolRef = fallbackToolRef; }
}
