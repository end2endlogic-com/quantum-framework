package com.e2eq.framework.model.persistent.morphia.planner;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.compiler.mongo.MongoAggregationCompiler;
import dev.morphia.query.filters.Filter;
import io.quarkus.logging.Log;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Minimal planner that decides whether to execute as a simple filter (current behavior)
 * or as an aggregation pipeline (when expand(...) is present). Aggregation mode returns
 * a pipeline compiled from a minimal LogicalPlan; the first stage remains a marker for
 * compatibility with existing tests.
 */
public class QueryPlanner {

    private LogicalPlan.PlannerProjection parseRootProjection(String query) {
        if (query == null) return null;
        int idx = query.indexOf("fields:[");
        if (idx < 0) return null;
        int start = idx + "fields:[".length();
        int end = query.indexOf(']', start);
        if (end < 0) return null; // malformed; ignore
        String inner = query.substring(start, end).trim();
        if (inner.isEmpty()) return null;
        java.util.Set<String> include = new java.util.LinkedHashSet<>();
        java.util.Set<String> exclude = new java.util.LinkedHashSet<>();
        boolean includeMode = false;
        // Split by commas not inside quotes (we don't expect quotes here, but be safe)
        for (String token : inner.split(",")) {
            if (token == null) continue;
            String t = token.trim();
            if (t.isEmpty()) continue;
            if (t.charAt(0) == '+') {
                includeMode = true;
                String path = t.substring(1).trim();
                if (!path.isEmpty()) include.add(path);
            } else if (t.charAt(0) == '-') {
                String path = t.substring(1).trim();
                if (!path.isEmpty()) exclude.add(path);
            } else {
                // If neither + nor -, treat as include by default to align with whitelist style
                includeMode = true;
                include.add(t);
            }
        }
        return new LogicalPlan.PlannerProjection(include, exclude, includeMode);
    }

    /**
     * Analyze the query and decide the execution mode, returning expand paths if present.
     */
    public PlannerResult analyze(String query) {
        if (query == null || query.isBlank()) {
            return new PlannerResult(PlannerResult.Mode.FILTER, List.of());
        }
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(query));
        BIAPIQueryParser parser = new BIAPIQueryParser(new CommonTokenStream(lexer));
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
                throw new IllegalStateException("Failed to parse query at line " + line + ": " + msg, e);
            }
        });
        ParseTree tree = parser.query();
        QueryAnalysisListener analysis = new QueryAnalysisListener();
        ParseTreeWalker.DEFAULT.walk(analysis, tree);
        PlannerResult.Mode mode = analysis.hasExpansions() ? PlannerResult.Mode.AGGREGATION : PlannerResult.Mode.FILTER;
        return new PlannerResult(mode, analysis.getExpandPaths());
    }

    public <T extends UnversionedBaseModel> PlannedQuery plan(String query, Class<T> modelClass) {
        return plan(query, modelClass, null, null, null);
    }

    // Remove expand(...) directives and fields:[...] projection from the raw query string
    private String stripExpandAndProjection(String query) {
        if (query == null) return null;
        String s = query;
        // remove expand(...) occurrences
        s = s.replaceAll("(?i)\\bexpand\\s*\\([^)]*\\)", "");
        // remove fields:[...]
        s = s.replaceAll("(?i)fields:\\[[^]]*\\]", "");
        // collapse duplicate logical operators and trim
        // remove leading/trailing && and ||, and compress multiples
        s = s.replaceAll("\\s*&&\\s*", " && ");
        s = s.replaceAll("\\s*\\|\\|\\s*", " || ");
        // remove operators at ends
        s = s.replaceAll("^(?:\\s*(?:&&|\\|\\|)\\s*)+", "");
        s = s.replaceAll("(?:\\s*(?:&&|\\|\\|)\\s*)+$", "");
        // remove multiple spaces
        s = s.trim().replaceAll("\\s{2,}", " ");
        return s.trim();
    }

    public <T extends UnversionedBaseModel> PlannedQuery plan(
            String query,
            Class<T> modelClass,
            Integer limit,
            Integer skip,
            List<LogicalPlan.SortSpec.Field> sortFields
    ) {
        PlannerResult result = analyze(query);
        if (result.getMode() == PlannerResult.Mode.FILTER) {
            // FILTER mode: use existing conversion to Morphia Filter
            if (query == null || query.isBlank()) {
                return PlannedQuery.forFilter(null);
            }
            return PlannedQuery.forFilter(MorphiaUtils.convertToFilter(query, modelClass));
        }
        // AGGREGATION mode: build a minimal LogicalPlan and compile it
        if (Log.isDebugEnabled()) {
            Log.debugf("Aggregation mode selected. expand paths=%s", result.getExpandPaths());
        }
        // Build minimal logical plan: depth=1, array flag inferred from path
        com.e2eq.framework.model.persistent.morphia.metadata.MetadataRegistry md =
                new com.e2eq.framework.model.persistent.morphia.metadata.DefaultMetadataRegistry();
        List<LogicalPlan.Expand> expands = result.getExpandPaths().stream()
                .map(p -> new LogicalPlan.Expand(
                        p,
                        1,
                        null,
                        p.contains("[*]"),
                        safeResolveJoin(md, modelClass, p)
                ))
                .collect(Collectors.toList());
        // Parse root projection fields:[...]
        LogicalPlan.PlannerProjection rootProj = parseRootProjection(query);
        // Optional validation of projection paths (hard error on unknown), special-case _id
        if (rootProj != null) {
            com.e2eq.framework.model.persistent.morphia.metadata.ProjectionSpec ps =
                    new com.e2eq.framework.model.persistent.morphia.metadata.ProjectionSpec(rootProj.include, rootProj.exclude, rootProj.includeMode);
            try {
                md.validateProjectionPaths(modelClass, ps);
            } catch (com.e2eq.framework.model.persistent.morphia.metadata.QueryMetadataException vex) {
                // allow _id as synthetic even if not on the class
                boolean onlyId = (rootProj.include.size() == 1 && rootProj.include.contains("_id"))
                        || (rootProj.exclude.size() == 1 && rootProj.exclude.contains("_id"));
                if (!onlyId) {
                    throw vex;
                }
            }
        }
        LogicalPlan.SortSpec sort = (sortFields != null && !sortFields.isEmpty()) ? new LogicalPlan.SortSpec(sortFields) : null;
        LogicalPlan.PageSpec page = (limit != null || skip != null) ? new LogicalPlan.PageSpec(limit, skip) : null;
        // Build root filter by stripping expand(...) and fields:[...] from the original query
        Filter rootFilter = null;
        String filterOnly = stripExpandAndProjection(query);
        if (filterOnly != null && !filterOnly.isBlank()) {
            try {
                rootFilter = MorphiaUtils.convertToFilter(filterOnly, modelClass);
            } catch (Exception ex) {
                // If filter parsing fails, proceed without a root $match to avoid breaking behavior
                if (Log.isDebugEnabled()) {
                    Log.debugf("Root filter parse failed after stripping expansions/projection: %s", ex.getMessage());
                }
            }
        }
        LogicalPlan plan = new LogicalPlan(modelClass, rootProj, expands, sort, page, rootFilter);
        MongoAggregationCompiler compiler = new MongoAggregationCompiler();
        List<Bson> pipeline = compiler.compile(plan);
        return PlannedQuery.forAggregation(pipeline);
    }

    private <T extends UnversionedBaseModel> com.e2eq.framework.model.persistent.morphia.metadata.JoinSpec safeResolveJoin(
            com.e2eq.framework.model.persistent.morphia.metadata.MetadataRegistry md,
            Class<T> modelClass,
            String path
    ) {
        try {
            return md.resolveJoin(modelClass, path);
        } catch (RuntimeException ex) {
            // Keep planning resilient in v1: log at debug and return null so compiler can fallback to placeholders
            if (Log.isDebugEnabled()) {
                Log.debugf("resolveJoin failed for path %s on %s: %s", path, modelClass.getName(), ex.getMessage());
            }
            return null;
        }
    }
}
