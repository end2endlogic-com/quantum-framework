package com.e2eq.framework.model.persistent.morphia.query;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.planner.PlannerResult;
import com.e2eq.framework.model.persistent.morphia.planner.QueryPlanner;

/**
 * Minimal implementation delegating to QueryPlanner.
 */
public class QueryGatewayImpl implements QueryGateway {

    private final QueryPlanner planner = new QueryPlanner();

    @Override
    public PlannerResult plan(String query, Class<? extends UnversionedBaseModel> rootType) {
        return planner.analyze(query);
    }
}
