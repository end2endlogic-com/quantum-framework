package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.planner.PlannedQuery;
import com.e2eq.framework.model.persistent.morphia.planner.PlannerResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PlannerDecisionTest {

    public static class DummyModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "test-area"; }
        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    @Test
    public void noExpand_usesFilterMode() {
        String q = "field1:123";
        PlannedQuery planned = MorphiaUtils.convertToPlannedQuery(q, DummyModel.class);
        assertEquals(PlannerResult.Mode.FILTER, planned.getMode());
        assertNotNull(planned.getFilter());
        assertTrue(planned.getAggregation().isEmpty());
    }

    @Test
    public void withExpand_usesAggregationMode() {
        String q = "expand(customer) && status:active";
        PlannedQuery planned = MorphiaUtils.convertToPlannedQuery(q, DummyModel.class);
        assertEquals(PlannerResult.Mode.AGGREGATION, planned.getMode());
        List<Bson> pipeline = planned.getAggregation();
        assertFalse(pipeline.isEmpty(), "expected a placeholder pipeline");
        // The first stage is a marker Document with planned paths (stub for now)
        assertTrue(pipeline.get(0) instanceof Document);
        Document d = (Document) pipeline.get(0);
        assertTrue(d.containsKey("$plannedExpandPaths"));
        assertEquals(List.of("customer"), d.get("$plannedExpandPaths"));
    }
}
