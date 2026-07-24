package com.e2eq.framework.bootstrap.model;

import java.time.Instant;
import java.util.Map;

public record BootstrapPackStepRun(
        String stepRef,
        BootstrapStepStatus status,
        BootstrapStepOutcome outcome,
        Map<String, Object> details,
        Instant startedAt,
        Instant completedAt
) {
    public BootstrapPackStepRun {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
