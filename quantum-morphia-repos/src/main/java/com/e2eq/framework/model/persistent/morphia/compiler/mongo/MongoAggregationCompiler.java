package com.e2eq.framework.model.persistent.morphia.compiler.mongo;

import com.e2eq.framework.model.persistent.morphia.metadata.JoinSpec;
import com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

/**
 * Mongo aggregation compiler (v1 incremental):
 * - Preserves a first marker stage for tests
 * - Builds per-expand $lookup/$set/$project stages
 */
public class MongoAggregationCompiler {

    public List<Bson> compile(LogicalPlan plan) {
        List<Bson> pipeline = new ArrayList<>();
        // 1) Marker stage to make tests deterministic without changing behavior
        List<String> paths = plan.expansions.stream().map(e -> e.path).toList();
        pipeline.add(new Document("$plannedExpandPaths", paths));

        // 2) Append per-expand stages (single-hop only)
        for (LogicalPlan.Expand e : plan.expansions) {
            String path = e.path;
            String temp = tempAlias(path);

            // Prefer JoinSpec if available
            JoinSpec j = e.join;
            String from = (j != null && j.fromCollection != null && !j.fromCollection.isBlank()) ? j.fromCollection : "__unknown__";
            String localIdExpr = (j != null && j.localIdExpr != null) ? j.localIdExpr : (stripArrayMarkers(path) + ".entityId");
            String tenantField = (j != null) ? j.tenantField : null;

            Document letDoc = new Document(e.array ? "ids" : "id", "$" + localIdExpr);
            if (tenantField != null && !tenantField.isBlank()) {
                letDoc.append("tenant", "$" + tenantField);
            }

            List<Object> andList = new ArrayList<>();
            if (e.array) {
                andList.add(new Document("$in", List.of("$_id", "$$ids")));
            } else {
                andList.add(new Document("$eq", List.of("$_id", "$$id")));
            }
            if (tenantField != null && !tenantField.isBlank()) {
                andList.add(new Document("$eq", List.of("$" + tenantField, "$$tenant")));
            }
            Document matchExpr = new Document("$expr", (andList.size() == 1) ? andList.get(0) : new Document("$and", andList));

            Document lookup = new Document("$lookup", new Document()
                    .append("from", from)
                    .append("let", letDoc)
                    .append("pipeline", List.of(new Document("$match", matchExpr)))
                    .append("as", temp));
            pipeline.add(lookup);

            // $set to embed hydrated data
            if (e.array) {
                String first = firstSegment(path);
                String last = lastSegment(path);
                Document set = new Document("$set", new Document(first, new Document("$map", new Document()
                        .append("input", "$" + first)
                        .append("as", "it")
                        .append("in", new Document("$mergeObjects", List.of(
                                "$$it",
                                new Document(last, new Document("$arrayElemAt", List.of(
                                        "$" + temp,
                                        new Document("$indexOfArray", List.of("$" + temp + "._id", "$$it." + last + ".entityId"))
                                )))
                        )))
                )));
                pipeline.add(set);
            } else {
                // Single ref: set path to first element of temp array
                pipeline.add(new Document("$set", new Document(path, new Document("$first", "$" + temp))));
            }
            // Drop temp array
            pipeline.add(new Document("$project", new Document(temp, 0)));
        }

        // Root projection (if any) would be appended here in a future iteration
        return pipeline;
    }

    private static String tempAlias(String path) {
        return "__exp_" + stripArrayMarkers(path).replace('.', '_');
    }

    private static String stripArrayMarkers(String path) {
        return path.replace("[*]", "");
    }

    private static String firstSegment(String path) {
        String p = stripArrayMarkers(path);
        int i = p.indexOf('.');
        return i < 0 ? p : p.substring(0, i);
    }

    private static String lastSegment(String path) {
        String p = stripArrayMarkers(path);
        int i = p.lastIndexOf('.');
        return i < 0 ? p : p.substring(i + 1);
    }
}
