package com.e2eq.framework.query;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.text.StringSubstitutor;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Convenience utilities to compile BIAPI queries into {@code Predicate<JsonNode>} and to
 * convert POJOs to {@link com.fasterxml.jackson.databind.JsonNode JsonNode} for evaluation.
 */
public final class QueryPredicates {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QueryPredicates() {}

    /**
     * Compiles a BIAPI query string into a {@code Predicate<JsonNode>} using the ANTLR parser.
     * @param query the BIAPI query string to compile
     * @param vars variables for ${var} substitution during parsing; may be null
     * @param objectVars object-valued variables (used notably for single-variable IN list expansion); may be null
     * @return a {@link java.util.function.Predicate Predicate} that evaluates a {@link com.fasterxml.jackson.databind.JsonNode JsonNode} according to the compiled query
     */
    public static Predicate<JsonNode> compilePredicate(String query, Map<String, String> vars, Map<String, Object> objectVars) {
        CharStream cs = CharStreams.fromString(query);
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);
        BIAPIQueryParser.QueryContext tree = parser.query();

        QueryToPredicateJsonListener listener = new QueryToPredicateJsonListener(vars, objectVars, new StringSubstitutor(vars));
        ParseTreeWalker.DEFAULT.walk(listener, tree);
        return listener.getPredicate();
    }

    /**
     * Converts a POJO to a Jackson JsonNode using the shared ObjectMapper.
     * @param pojo the source object to convert (may be null)
     * @return a JsonNode view of the object; never null (null pojo becomes a JSON null node)
     */
    public static JsonNode toJsonNode(Object pojo) {
        return MAPPER.valueToTree(pojo);
    }
}
