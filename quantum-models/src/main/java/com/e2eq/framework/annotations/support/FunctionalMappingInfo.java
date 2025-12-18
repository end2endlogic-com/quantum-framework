package com.e2eq.framework.annotations.support;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight holder for resolved Functional Mapping information.
 */
public final class FunctionalMappingInfo {
    public final @Nullable String area;
    public final @Nullable String domain;
    public final boolean fromAnnotation;

    public FunctionalMappingInfo(@Nullable String area, @Nullable String domain, boolean fromAnnotation) {
        this.area = area;
        this.domain = domain;
        this.fromAnnotation = fromAnnotation;
    }

    public static FunctionalMappingInfo annotated(@NotNull String area, @NotNull String domain) {
        return new FunctionalMappingInfo(area, domain, true);
    }

    public static FunctionalMappingInfo noAnnotation() {
        return new FunctionalMappingInfo(null, null, false);
    }
}
