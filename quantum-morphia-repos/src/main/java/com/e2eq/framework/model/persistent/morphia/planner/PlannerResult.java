package com.e2eq.framework.model.persistent.morphia.planner;

import java.util.Collections;
import java.util.List;

public class PlannerResult {
    public enum Mode { FILTER, AGGREGATION }

    private final Mode mode;
    private final List<String> expandPaths;
    private final List<String> relationExpands;

    public PlannerResult(Mode mode, List<String> expandPaths) {
        this(mode, expandPaths, null);
    }

    public PlannerResult(Mode mode, List<String> expandPaths, List<String> relationExpands) {
        this.mode = mode;
        this.expandPaths = expandPaths == null ? Collections.emptyList() : List.copyOf(expandPaths);
        this.relationExpands = relationExpands == null ? Collections.emptyList() : List.copyOf(relationExpands);
    }

    public Mode getMode() {
        return mode;
    }

    /** Field-path expands, compiled to {@code $lookup}; never includes relation expands. */
    public List<String> getExpandPaths() {
        return expandPaths;
    }

    /**
     * Relations named by {@code expand(@relation)}. They are not compiled into the pipeline: the
     * caller hydrates them after the aggregation runs, through {@link RelationExpansion}.
     */
    public List<String> getRelationExpands() {
        return relationExpands;
    }

    @Override
    public String toString() {
        return "PlannerResult{" +
                "mode=" + mode +
                ", expandPaths=" + expandPaths +
                ", relationExpands=" + relationExpands +
                '}';
    }
}
