package com.e2eq.framework.model.persistent.morphia.compiler.mongo;

import com.e2eq.framework.model.persistent.morphia.metadata.JoinSpec;
import com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.LogicalFilter;
import dev.morphia.query.filters.RegexFilter;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Mongo aggregation compiler (v1 incremental):
 * - Preserves a first marker stage for tests
 * - Builds per-expand $lookup/$set/$project stages
 */
public class MongoAggregationCompiler {

    private final MorphiaDatastore datastore;

    public MongoAggregationCompiler() {
        this(null);
    }

    public MongoAggregationCompiler(MorphiaDatastore datastore) {
        this.datastore = datastore;
    }

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

            JoinSpec j = e.join;
            if (j == null || j.fromCollection == null || j.fromCollection.isBlank()
                    || j.localIdExpr == null || j.localIdExpr.isBlank()) {
                throw new IllegalArgumentException(
                        "Expand path '" + path + "' has no complete join metadata; aggregation denied");
            }
            String from = j.fromCollection;
            String localIdExpr = j.localIdExpr;
            String tenantField = j.tenantField;

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

    /**
     * Compiles a Morphia filter into a Mongo {@code $match} document.
     * Unsupported filter shapes fail closed; they are never silently dropped.
     */
    public Document compileMatch(Filter filter) {
        return toMatch(filter);
    }

    private Document toMatch(Filter filter) {
        if (filter == null) return null;
        String name = filter.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Morphia filter has no operator; aggregation denied");
        }
        if (filter instanceof LogicalFilter logical) {
            List<Document> children = logical.filters().stream().map(this::toMatch).toList();
            if (children.isEmpty()) {
                throw new IllegalArgumentException("Logical filter " + name + " has no operands");
            }
            return new Document(name, children);
        }

        String field = datastore == null ? filter.getField() : filter.path(datastore.getMapper());
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Morphia filter " + name + " has no field; aggregation denied");
        }
        if (filter instanceof RegexFilter regex) {
            Pattern pattern = regex.pattern();
            if (pattern == null) {
                throw new IllegalArgumentException("Regex filter for " + field + " has no pattern");
            }
            Document expression = new Document("$regex", pattern.pattern());
            String options = regexOptions(pattern.flags());
            if (!options.isEmpty()) {
                expression.append("$options", options);
            }
            return new Document(field, filter.isNot() ? new Document("$not", expression) : expression);
        }

        Object value = datastore == null ? filter.getValue() : filter.getValue(datastore);
        Document expression;
        switch (name) {
            case "$eq":
                if (!filter.isNot()) {
                    return new Document(field, value);
                }
                expression = new Document("$eq", value);
                break;
            case "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$exists", "$size", "$all":
                expression = new Document(name, value);
                break;
            default:
                throw new IllegalArgumentException(
                    "Unsupported Morphia filter operator '" + name + "' for aggregation; denied");
        }
        return new Document(field, filter.isNot() ? new Document("$not", expression) : expression);
    }

    private String regexOptions(int flags) {
        StringBuilder options = new StringBuilder();
        if ((flags & Pattern.CASE_INSENSITIVE) != 0) options.append('i');
        if ((flags & Pattern.MULTILINE) != 0) options.append('m');
        if ((flags & Pattern.DOTALL) != 0) options.append('s');
        if ((flags & Pattern.COMMENTS) != 0) options.append('x');
        return options.toString();
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
