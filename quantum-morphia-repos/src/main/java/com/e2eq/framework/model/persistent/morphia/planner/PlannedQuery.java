package com.e2eq.framework.model.persistent.morphia.planner;

import dev.morphia.query.filters.Filter;
import org.bson.conversions.Bson;

import java.util.Collections;
import java.util.List;

public class PlannedQuery {
    private final PlannerResult.Mode mode;
    private final Filter filter; // present when mode == FILTER
    private final List<Bson> aggregation; // present when mode == AGGREGATION

    private PlannedQuery(PlannerResult.Mode mode, Filter filter, List<Bson> aggregation) {
        this.mode = mode;
        this.filter = filter;
        this.aggregation = aggregation == null ? Collections.emptyList() : List.copyOf(aggregation);
    }

    public static PlannedQuery forFilter(Filter f) {
        return new PlannedQuery(PlannerResult.Mode.FILTER, f, null);
    }

    public static PlannedQuery forAggregation(List<Bson> pipeline) {
        return new PlannedQuery(PlannerResult.Mode.AGGREGATION, null, pipeline);
        }

    public PlannerResult.Mode getMode() { return mode; }
    public Filter getFilter() { return filter; }
    public List<Bson> getAggregation() { return aggregation; }
}
