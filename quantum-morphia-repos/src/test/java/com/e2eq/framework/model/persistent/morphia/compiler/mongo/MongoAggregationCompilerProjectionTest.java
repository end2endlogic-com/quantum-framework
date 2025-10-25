package com.e2eq.framework.model.persistent.morphia.compiler.mongo;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class MongoAggregationCompilerProjectionTest {

    static class Dummy extends UnversionedBaseModel {
        @Override public String bmFunctionalArea() { return "a"; }
        @Override public String bmFunctionalDomain() { return "d"; }
    }

    @Test
    public void compile_withRootProjection_appendsProjectStage() {
        // Root projection: include _id and total, exclude internalNotes
        LogicalPlan.PlannerProjection proj = new LogicalPlan.PlannerProjection(
                Set.of("_id", "total"), Set.of("internalNotes"), true
        );
        LogicalPlan plan = new LogicalPlan(Dummy.class, proj, java.util.List.of(), null, null);

        MongoAggregationCompiler c = new MongoAggregationCompiler();
        List<Bson> pipeline = c.compile(plan);

        assertFalse(pipeline.isEmpty());
        // The last stage should be a $project with our fields
        Bson last = pipeline.get(pipeline.size() - 1);
        assertTrue(last instanceof Document);
        Document d = (Document) last;
        assertTrue(d.containsKey("$project"));
        Document p = d.get("$project", Document.class);
        assertNotNull(p);
        assertEquals(1, p.get("_id"));
        assertEquals(1, p.get("total"));
        assertEquals(0, p.get("internalNotes"));
    }
}
