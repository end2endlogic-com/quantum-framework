package com.e2eq.framework.model.persistent.morphia.metadata;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Unified projection spec used by planner/compiler. includeMode=true when any '+' entries exist.
 */
public class ProjectionSpec {
    public final Set<String> include;
    public final Set<String> exclude;
    public final boolean includeMode;

    public ProjectionSpec(Set<String> include, Set<String> exclude, boolean includeMode) {
        this.include = include == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(include));
        this.exclude = exclude == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(exclude));
        this.includeMode = includeMode;
    }
}
