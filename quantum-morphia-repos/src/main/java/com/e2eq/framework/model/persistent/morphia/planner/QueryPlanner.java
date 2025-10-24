package com.e2eq.framework.model.persistent.morphia.planner;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import io.quarkus.logging.Log;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal planner that decides whether to execute as a simple filter (current behavior)
 * or as an aggregation pipeline (when expand(...) is present). For now, aggregation
 * pipeline is returned as a stub list for future compiler work.
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
        // AGGREGATION mode: for now, return a stub pipeline and log the paths.
        if (Log.isDebugEnabled()) {
            Log.debugf("Aggregation mode selected. expand paths=%s", result.getExpandPaths());
        }
        List<Bson> pipeline = new ArrayList<>();
        pipeline.add(new Document("$plannedExpandPaths", result.getExpandPaths()));
        return PlannedQuery.forAggregation(pipeline);
    }
}
