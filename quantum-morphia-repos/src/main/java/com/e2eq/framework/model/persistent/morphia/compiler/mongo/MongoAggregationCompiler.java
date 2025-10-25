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

        // 1.25) Root $match from simple filters (when available). Insert before sort/limit.
        Document match = toMatch(plan.rootFilter);
        if (match != null && !match.isEmpty()) {
            pipeline.add(new Document("$match", match));
        }

        // 1.5) Root stages: $sort → $skip → $limit (apply before lookups when present)
        if (plan.sort != null && plan.sort.fields != null && !plan.sort.fields.isEmpty()) {
            Document sort = new Document();
            for (LogicalPlan.SortSpec.Field f : plan.sort.fields) {
                if (f != null && f.name != null && !f.name.isBlank()) {
                    sort.append(f.name, f.dir >= 0 ? 1 : -1);
                }
            }
            if (!sort.isEmpty()) {
                pipeline.add(new Document("$sort", sort));
            }
        }
        if (plan.page != null) {
            if (plan.page.skip != null && plan.page.skip > 0) {
                pipeline.add(new Document("$skip", plan.page.skip));
            }
            if (plan.page.limit != null && plan.page.limit > 0) {
                pipeline.add(new Document("$limit", plan.page.limit));
            }
        }

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

        // Root projection (if any)
        if (plan.rootProjection != null) {
            Document projDoc = new Document();
            if (plan.rootProjection.includeMode) {
                for (String inc : plan.rootProjection.include) {
                    projDoc.append(inc, 1);
                }
                for (String exc : plan.rootProjection.exclude) {
                    projDoc.append(exc, 0);
                }
                // preserve default _id unless explicitly excluded or included
                if (!projDoc.containsKey("_id")) {
                    projDoc.append("_id", 1);
                }
            } else {
                for (String exc : plan.rootProjection.exclude) {
                    projDoc.append(exc, 0);
                }
            }
            if (!projDoc.isEmpty()) {
                pipeline.add(new Document("$project", projDoc));
            }
        }
        return pipeline;
    }

    // Minimal translator for common Morphia Filter operators to $match
    private Document toMatch(dev.morphia.query.filters.Filter filter) {
        if (filter == null) return null;
        try {
            String name = filter.getName();
            String field = filter.getField();
            Object value = filter.getValue();
            if (name == null) return new Document();
            // Equality can be emitted as direct field match for simplicity
            switch (name) {
                case "$eq":
                    return new Document(field, value);
                case "$ne":
                    return new Document(field, new Document("$ne", value));
                case "$gt":
                    return new Document(field, new Document("$gt", value));
                case "$gte":
                    return new Document(field, new Document("$gte", value));
                case "$lt":
                    return new Document(field, new Document("$lt", value));
                case "$lte":
                    return new Document(field, new Document("$lte", value));
                case "$in":
                    return new Document(field, new Document("$in", value));
                case "$nin":
                    return new Document(field, new Document("$nin", value));
                case "$exists":
                    return new Document(field, new Document("$exists", value));
                default:
                    // Special handling for RegexFilter (Morphia)
                    if (filter instanceof dev.morphia.query.filters.RegexFilter rf) {
                        String f = rf.getField();
                        Object v = rf.getValue();
                        return new Document(f, new Document("$regex", v));
                    }
                    // Fallback: return empty to avoid emitting an invalid $match
                    return new Document();
            }
        } catch (Throwable t) {
            // Be conservative: on any unexpected shape, skip $match
            return new Document();
        }
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
