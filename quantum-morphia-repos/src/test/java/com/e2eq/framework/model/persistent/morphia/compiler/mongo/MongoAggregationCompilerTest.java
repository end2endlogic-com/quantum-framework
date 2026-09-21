package com.e2eq.framework.model.persistent.morphia.compiler.mongo;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.metadata.JoinSpec;
import com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan;
import dev.morphia.query.filters.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

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

    @Test
    void compileMatchPreservesLogicalPolicyFilters() {
        MongoAggregationCompiler compiler = new MongoAggregationCompiler();

        Document match = compiler.compileMatch(Filters.and(
                Filters.eq("status", "ACTIVE"),
                Filters.in("dataDomain.tenantId", List.of("TENANT-A", "TENANT-B"))));

        List<?> clauses = match.getList("$and", Object.class);
        assertEquals(2, clauses.size());
        assertEquals("ACTIVE", ((Document) clauses.get(0)).getString("status"));
        assertEquals(List.of("TENANT-A", "TENANT-B"),
                ((Document) ((Document) clauses.get(1)).get("dataDomain.tenantId")).get("$in"));
    }

    @Test
    void compileMatchPreservesRegexFlags() {
        MongoAggregationCompiler compiler = new MongoAggregationCompiler();

        Document match = compiler.compileMatch(
                Filters.regex("displayName", Pattern.compile("century", Pattern.CASE_INSENSITIVE)));

        Document expression = (Document) match.get("displayName");
        assertEquals("century", expression.getString("$regex"));
        assertEquals("i", expression.getString("$options"));
    }

    @Test
    void unsupportedPolicyFilterFailsClosed() {
        MongoAggregationCompiler compiler = new MongoAggregationCompiler();

        assertThrows(IllegalArgumentException.class,
                () -> compiler.compileMatch(Filters.mod("score", 5, 0)));
    }

    @Test
    void expandWithoutJoinMetadataFailsClosed() {
        LogicalPlan.Expand expansion = new LogicalPlan.Expand("customer", 1, null, false, null);
        LogicalPlan plan = new LogicalPlan(Dummy.class, null, List.of(expansion), null, null);

        assertThrows(IllegalArgumentException.class,
                () -> new MongoAggregationCompiler().compile(plan));
    }

    @Test
    void compile_withExpandFilter_injectsInnerMatchStage() {
        JoinSpec js = new JoinSpec("customers", "_id", "customer.entityId", "dataDomain.tenantId", false);
        dev.morphia.query.filters.Filter targetFilter = Filters.and(
                Filters.eq("status", "ACTIVE"),
                Filters.eq("tier", "PLATINUM")
        );
        LogicalPlan.Expand exp = new LogicalPlan.Expand("customer", 1, null, false, js, targetFilter);
        LogicalPlan plan = new LogicalPlan(Dummy.class, null, List.of(exp), null, null);

        MongoAggregationCompiler c = new MongoAggregationCompiler();
        List<Bson> pipeline = c.compile(plan);

        Document lookupStage = (Document) pipeline.stream()
                .filter(b -> b instanceof Document && ((Document) b).containsKey("$lookup"))
                .findFirst().orElseThrow();
        Document lookup = lookupStage.get("$lookup", Document.class);
        List<?> innerStages = lookup.getList("pipeline", Object.class);
        assertEquals(2, innerStages.size(), "Inner pipeline should have join $match and policy $match");

        Document firstMatch = (Document) innerStages.get(0);
        assertTrue(firstMatch.containsKey("$match"));
        assertTrue(((Document) firstMatch.get("$match")).containsKey("$expr"));

        Document secondMatch = (Document) innerStages.get(1);
        assertTrue(secondMatch.containsKey("$match"));
        Document matchDoc = (Document) secondMatch.get("$match");
        assertTrue(matchDoc.containsKey("$and"));
        List<?> clauses = matchDoc.getList("$and", Object.class);
        assertEquals(2, clauses.size());
        assertEquals("ACTIVE", ((Document) clauses.get(0)).getString("status"));
        assertEquals("PLATINUM", ((Document) clauses.get(1)).getString("tier"));
    }

    @Test
    void compile_withExpandProjection_injectsInnerProjectStage() {
        JoinSpec js = new JoinSpec("customers", "_id", "customer.entityId", "dataDomain.tenantId", false);
        LogicalPlan.PlannerProjection proj = new LogicalPlan.PlannerProjection(
                java.util.Set.of("name", "email"), java.util.Set.of("ssn"), true
        );
        LogicalPlan.Expand exp = new LogicalPlan.Expand("customer", 1, proj, false, js);
        LogicalPlan plan = new LogicalPlan(Dummy.class, null, List.of(exp), null, null);

        MongoAggregationCompiler c = new MongoAggregationCompiler();
        List<Bson> pipeline = c.compile(plan);

        Document lookupStage = (Document) pipeline.stream()
                .filter(b -> b instanceof Document && ((Document) b).containsKey("$lookup"))
                .findFirst().orElseThrow();
        Document lookup = lookupStage.get("$lookup", Document.class);
        List<?> innerStages = lookup.getList("pipeline", Object.class);
        assertEquals(2, innerStages.size(), "Inner pipeline should have join $match and $project");

        Document projectStage = (Document) innerStages.get(1);
        assertTrue(projectStage.containsKey("$project"));
        Document projDoc = (Document) projectStage.get("$project");
        assertEquals(1, projDoc.get("name"));
        assertEquals(1, projDoc.get("email"));
        assertEquals(0, projDoc.get("ssn"));
        assertEquals(1, projDoc.get("_id"));
    }

    @Test
    void compile_withStagePolicies_injectsInnerMatchAndExclusionProject() {
        JoinSpec js = new JoinSpec("suppliers", "_id", "supplier.entityId", "dataDomain.tenantId", false);
        LogicalPlan.Expand exp = new LogicalPlan.Expand("supplier", 1, null, false, js);
        LogicalPlan plan = new LogicalPlan(Dummy.class, null, List.of(exp), null, null);

        MongoAggregationCompiler c = new MongoAggregationCompiler();
        MongoAggregationCompiler.StagePolicy policy = new MongoAggregationCompiler.StagePolicy(
                Filters.eq("active", true),
                java.util.Set.of("taxId", "internalRating")
        );
        List<Bson> pipeline = c.compile(plan, java.util.Map.of("supplier", policy));

        Document lookupStage = (Document) pipeline.stream()
                .filter(b -> b instanceof Document && ((Document) b).containsKey("$lookup"))
                .findFirst().orElseThrow();
        Document lookup = lookupStage.get("$lookup", Document.class);
        List<?> innerStages = lookup.getList("pipeline", Object.class);
        assertEquals(3, innerStages.size(), "Inner pipeline should have join $match, policy $match, and policy $project");

        Document policyMatch = (Document) innerStages.get(1);
        assertTrue(policyMatch.containsKey("$match"));
        assertEquals(true, ((Document) policyMatch.get("$match")).get("active"));

        Document policyProject = (Document) innerStages.get(2);
        assertTrue(policyProject.containsKey("$project"));
        Document projDoc = (Document) policyProject.get("$project");
        assertEquals(0, projDoc.get("taxId"));
        assertEquals(0, projDoc.get("internalRating"));
    }
}
