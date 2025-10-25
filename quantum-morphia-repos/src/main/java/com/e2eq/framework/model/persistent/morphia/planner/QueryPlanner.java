package com.e2eq.framework.model.persistent.morphia.planner;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.compiler.mongo.MongoAggregationCompiler;
import io.quarkus.logging.Log;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Minimal planner that decides whether to execute as a simple filter (current behavior)
 * or as an aggregation pipeline (when expand(...) is present). Aggregation mode returns
 * a pipeline compiled from a minimal LogicalPlan; the first stage remains a marker for
 * compatibility with existing tests.
 */
public class QueryPlanner {

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
        LogicalPlan plan = new LogicalPlan(modelClass, null, expands, null, null);
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
