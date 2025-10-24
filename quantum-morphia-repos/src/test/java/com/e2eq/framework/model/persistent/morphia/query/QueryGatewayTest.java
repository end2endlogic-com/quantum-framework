package com.e2eq.framework.model.persistent.morphia.query;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.planner.PlannerResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class QueryGatewayTest {

    static class Dummy extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "a"; }
        @Override
        public String bmFunctionalDomain() { return "d"; }
    }

    private final QueryGateway gateway = new QueryGatewayImpl();

    @Test
    public void plan_noExpand_filterMode() {
        PlannerResult pr = gateway.plan("status:active", Dummy.class);
        assertEquals(PlannerResult.Mode.FILTER, pr.getMode());
        assertTrue(pr.getExpandPaths().isEmpty());
    }

    @Test
    public void plan_withExpand_aggregationMode() {
        PlannerResult pr = gateway.plan("expand(customer) && status:active", Dummy.class);
        assertEquals(PlannerResult.Mode.AGGREGATION, pr.getMode());
        assertEquals(1, pr.getExpandPaths().size());
        assertEquals("customer", pr.getExpandPaths().get(0));
    }
}
