package com.e2eq.framework.model.persistent.morphia.query;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.planner.PlannerResult;

/**
 * Collection-agnostic query facade exposing planner decisions.
 * For v1 it only supports planning/inspection. Execution will be added later.
 */
public interface QueryGateway {
    PlannerResult plan(String query, Class<? extends UnversionedBaseModel> rootType);
}
