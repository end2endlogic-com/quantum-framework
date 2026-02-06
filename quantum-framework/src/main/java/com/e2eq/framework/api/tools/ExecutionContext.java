package com.e2eq.framework.api.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Context passed to {@link ToolExecutor} for realm, principal, audit, and caller identity.
 */
@RegisterForReflection
public class ExecutionContext {

    private String realmId;
    private String principalId;
    private String correlationId;
    private String traceId;
    /** Which agent is invoking (null if workflow or direct call). */
    private String agentRef;
    /** Which workflow instance is invoking (null if agent or direct). */
    private String workflowInstanceId;
    /** If true, tool call is journaled by Restate for crash recovery. */
    private boolean durable;

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

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getAgentRef() {
        return agentRef;
    }

    public void setAgentRef(String agentRef) {
        this.agentRef = agentRef;
    }

    public String getWorkflowInstanceId() {
        return workflowInstanceId;
    }

    public void setWorkflowInstanceId(String workflowInstanceId) {
        this.workflowInstanceId = workflowInstanceId;
    }

    public boolean isDurable() {
        return durable;
    }

    public void setDurable(boolean durable) {
        this.durable = durable;
    }
}
