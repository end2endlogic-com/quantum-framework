package com.e2eq.framework.bootstrap.model;

import java.util.Map;

public record BootstrapStepRequest(
        String packRef,
        String packVersion,
        String productRef,
        String profileRef,
        String stepRef,
        BootstrapStepKind kind,
        BootstrapPackApplyMode mode,
        String actorRef,
        Map<String, Object> scope,
        Map<String, Object> config
) {
    public BootstrapStepRequest {
        scope = scope == null ? Map.of() : Map.copyOf(scope);
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
