package com.e2eq.framework.bootstrap.model;

import java.util.List;
import java.util.Map;

public record BootstrapPackStepDefinition(
        String stepRef,
        String description,
        BootstrapStepKind kind,
        BootstrapStepApplyPolicy applyPolicy,
        List<String> dependsOn,
        Map<String, Object> config
) {
    public BootstrapPackStepDefinition {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
