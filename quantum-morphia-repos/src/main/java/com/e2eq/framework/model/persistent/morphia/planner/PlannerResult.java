package com.e2eq.framework.model.persistent.morphia.planner;

import java.util.Collections;
import java.util.List;

public class PlannerResult {
    public enum Mode { FILTER, AGGREGATION }

    private final Mode mode;
    private final List<String> expandPaths;

    public PlannerResult(Mode mode, List<String> expandPaths) {
        this.mode = mode;
        this.expandPaths = expandPaths == null ? Collections.emptyList() : List.copyOf(expandPaths);
    }

    public Mode getMode() {
        return mode;
    }

    public List<String> getExpandPaths() {
        return expandPaths;
    }

    @Override
    public String toString() {
        return "PlannerResult{" +
                "mode=" + mode +
                ", expandPaths=" + expandPaths +
                '}';
    }
}
