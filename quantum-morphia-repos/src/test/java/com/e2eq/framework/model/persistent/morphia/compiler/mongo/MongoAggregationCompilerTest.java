package com.e2eq.framework.model.persistent.morphia.compiler.mongo;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.metadata.JoinSpec;
import com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MongoAggregationCompilerTest {

    static class Dummy extends UnversionedBaseModel {
        @Override public String bmFunctionalArea() { return "a"; }
        @Override public String bmFunctionalDomain() { return "d"; }
    }

    @Test
    public void compile_singleRefProducesLookupSetProject() {
        // Build a minimal logical plan with one expand path and a JoinSpec
        JoinSpec js = new JoinSpec("customers", "_id", "customer.entityId", "dataDomain.tenantId", false);
        LogicalPlan.Expand exp = new LogicalPlan.Expand("customer", 1, null, false, js);
        LogicalPlan plan = new LogicalPlan(Dummy.class, null, java.util.List.of(exp), null, null);

        MongoAggregationCompiler c = new MongoAggregationCompiler();
        List<Bson> pipeline = c.compile(plan);

        assertFalse(pipeline.isEmpty());
        // First stage remains a marker
        assertTrue(pipeline.get(0) instanceof Document);
        Document first = (Document) pipeline.get(0);
        assertTrue(first.containsKey("$plannedExpandPaths"));

        // Should contain a $lookup stage
        boolean hasLookup = pipeline.stream().anyMatch(b -> b instanceof Document && ((Document) b).containsKey("$lookup"));
        assertTrue(hasLookup, "Expected a $lookup stage");
        // Should contain a $set stage embedding the path
        boolean hasSet = pipeline.stream().anyMatch(b -> b instanceof Document && ((Document) b).containsKey("$set"));
        assertTrue(hasSet, "Expected a $set stage");
        // Should drop the temp alias with $project
        boolean hasProject = pipeline.stream().anyMatch(b -> b instanceof Document && ((Document) b).containsKey("$project"));
        assertTrue(hasProject, "Expected a $project cleanup stage");
    }
}
