package com.e2eq.framework.bootstrap.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BootstrapPackRun(
        String packRunRef,
        String packRef,
        String packVersion,
        String productRef,
        String profileRef,
        BootstrapPackApplyMode mode,
        BootstrapPackRunStatus status,
        Map<String, Object> scope,
        List<BootstrapPackStepRun> steps,
        Instant startedAt,
        Instant completedAt
) {
    public BootstrapPackRun {
        scope = scope == null ? Map.of() : Map.copyOf(scope);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
