package com.e2eq.framework.api.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Result of invoking a gateway tool (status code and response entity).
 * Used by {@link AgentExecuteHandler} and {@link com.e2eq.framework.api.tools.ToolExecutor}.
 */
@RegisterForReflection
public class GatewayInvocationResult {

    private final int statusCode;
    private final Object entity;
    private final String errorMessage;

    public GatewayInvocationResult(int statusCode, Object entity, String errorMessage) {
        this.statusCode = statusCode;
        this.entity = entity;
        this.errorMessage = errorMessage;
    }

    public static GatewayInvocationResult ok(Object entity) {
        return new GatewayInvocationResult(200, entity, null);
    }

    public static GatewayInvocationResult error(int statusCode, String errorMessage) {
        return new GatewayInvocationResult(statusCode, null, errorMessage);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Object getEntity() {
        return entity;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
