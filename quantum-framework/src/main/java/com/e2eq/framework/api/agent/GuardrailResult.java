package com.e2eq.framework.api.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Result of guardrail evaluation: allow execution, deny, or require user confirmation.
 */
@RegisterForReflection
public enum GuardrailResult {
    /** Tool call is allowed. */
    ALLOW,
    /** Tool call is blocked (e.g. no_delete). */
    DENY,
    /** Execution should pause for user confirmation (e.g. side-effect with requiresApproval). */
    REQUIRE_CONFIRMATION
}
