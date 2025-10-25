package com.e2eq.framework.model.persistent.morphia.compiler.mongo;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MongoAggregationCompilerRootStagesTest {

    static class Dummy extends UnversionedBaseModel {
        @Override public String bmFunctionalArea() { return "a"; }
        @Override public String bmFunctionalDomain() { return "d"; }
    }

    @Test
    public void compile_includesRootSortSkipLimitBeforeLookups() {
        // Plan with sort and paging but no expansions
        LogicalPlan.SortSpec.Field f1 = new LogicalPlan.SortSpec.Field("createdDate", -1);
        LogicalPlan.SortSpec sort = new LogicalPlan.SortSpec(java.util.List.of(f1));
        LogicalPlan.PageSpec page = new LogicalPlan.PageSpec(25, 10);
        LogicalPlan plan = new LogicalPlan(Dummy.class, null, java.util.List.of(), sort, page);

        MongoAggregationCompiler c = new MongoAggregationCompiler();
        List<Bson> pipeline = c.compile(plan);

        assertFalse(pipeline.isEmpty());
        // First stage is the marker
        assertTrue(pipeline.get(0) instanceof Document);
        Document d0 = (Document) pipeline.get(0);
        assertTrue(d0.containsKey("$plannedExpandPaths"));

        // Should contain $sort, $skip, $limit in this order following the marker
        assertTrue(pipeline.size() >= 4);
        Document sortDoc = (Document) pipeline.get(1);
        assertTrue(sortDoc.containsKey("$sort"));
        Document sortSpec = sortDoc.get("$sort", Document.class);
        assertEquals(-1, sortSpec.get("createdDate"));

        Document skipDoc = (Document) pipeline.get(2);
        assertTrue(skipDoc.containsKey("$skip"));
        assertEquals(10, skipDoc.getInteger("$skip"));

        Document limitDoc = (Document) pipeline.get(3);
        assertTrue(limitDoc.containsKey("$limit"));
        assertEquals(25, limitDoc.getInteger("$limit"));
    }
}
