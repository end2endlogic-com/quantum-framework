package com.e2eq.framework.bootstrap.model;

import java.util.Map;

public record BootstrapStepResult(
        BootstrapStepStatus status,
        BootstrapStepOutcome outcome,
        Map<String, Object> details
) {
    public BootstrapStepResult {
        if (status == null || outcome == null)
            throw new IllegalArgumentException("BOOTSTRAP_STEP_RESULT_REQUIRED: status and outcome must be explicit");
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
