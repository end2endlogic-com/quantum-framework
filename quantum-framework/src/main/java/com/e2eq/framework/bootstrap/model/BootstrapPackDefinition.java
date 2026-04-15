package com.e2eq.framework.bootstrap.model;

import java.util.List;

public record BootstrapPackDefinition(
        String packRef,
        String packVersion,
        String productRef,
        String profileRef,
        List<BootstrapPackStepDefinition> steps
) {
    public BootstrapPackDefinition {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
