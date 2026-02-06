package com.e2eq.framework.api.agent;

import com.e2eq.framework.model.persistent.agent.Agent;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/**
 * Evaluates guardrails before executing a tool call (e.g. no_delete, require_confirmation).
 * Plugged in before {@link com.e2eq.framework.api.tools.ToolExecutor} in the agent loop.
 */
@ApplicationScoped
public class GuardrailEvaluator {

    /**
     * Evaluates whether the tool call is allowed for this agent.
     *
     * @param agent  agent definition (e.g. requiresApproval)
     * @param tool   tool being invoked
     * @param params tool arguments
     * @return ALLOW, DENY, or REQUIRE_CONFIRMATION
     */
    public GuardrailResult evaluate(Agent agent, ToolDefinition tool, Map<String, Object> params) {
        if (agent == null || tool == null) {
            return GuardrailResult.ALLOW;
        }
        String refName = tool.getRefName() != null ? tool.getRefName().toLowerCase() : "";
        if (refName.contains("delete") && agent.isRequiresApproval()) {
            return GuardrailResult.REQUIRE_CONFIRMATION;
        }
        if (tool.isHasSideEffects() && agent.isRequiresApproval()) {
            return GuardrailResult.REQUIRE_CONFIRMATION;
        }
        return GuardrailResult.ALLOW;
    }
}
