package com.e2eq.framework.bootstrap.model;

import java.util.Map;

public record BootstrapStepResult(
        BootstrapStepStatus status,
        BootstrapStepOutcome outcome,
        Map<String, Object> details
) {
    public BootstrapStepResult {
        status = status == null ? BootstrapStepStatus.COMPLETED : status;
        outcome = outcome == null ? BootstrapStepOutcome.ALREADY_PRESENT : outcome;
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
