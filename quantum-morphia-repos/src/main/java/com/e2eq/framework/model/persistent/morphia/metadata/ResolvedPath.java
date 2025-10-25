package com.e2eq.framework.model.persistent.morphia.metadata;

import java.util.List;

public class ResolvedPath {
    public final Class<?> targetType;
    public final boolean isArray;
    public final boolean isReference;
    public final boolean stoppedAtBoundary;
    public final List<PathSegment> segments;

    public ResolvedPath(Class<?> targetType, boolean isArray, boolean isReference, boolean stoppedAtBoundary, List<PathSegment> segments) {
        this.targetType = targetType;
        this.isArray = isArray;
        this.isReference = isReference;
        this.stoppedAtBoundary = stoppedAtBoundary;
        this.segments = segments;
    }
}
