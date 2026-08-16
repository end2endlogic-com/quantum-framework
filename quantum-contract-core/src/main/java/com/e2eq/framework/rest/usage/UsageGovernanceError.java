package com.e2eq.framework.rest.usage;

/** Stable cross-module error contract for usage rejection and enforcement-state failure. */
public record UsageGovernanceError(
        int status,
        String code,
        String message,
        String endpointId) {
}
