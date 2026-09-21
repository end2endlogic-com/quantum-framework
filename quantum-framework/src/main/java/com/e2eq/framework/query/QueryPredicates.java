package com.e2eq.framework.query.runtime;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
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
        return compilePredicate(query, vars, objectVars, null);
    }

    /**
     * Compiles a BIAPI query string into a {@code Predicate<JsonNode>} using the ANTLR parser with model context.
     * @param query the BIAPI query string to compile
     * @param vars variables for ${var} substitution during parsing; may be null
     * @param objectVars object-valued variables; may be null
     * @param modelClass target model class for ontology domain/range validation; may be null
     * @return a {@link java.util.function.Predicate Predicate} that evaluates a {@link com.fasterxml.jackson.databind.JsonNode JsonNode} according to the compiled query
     */
    public static Predicate<JsonNode> compilePredicate(String query, Map<String, String> vars, Map<String, Object> objectVars, Class<? extends UnversionedBaseModel> modelClass) {
        String normalized = normalizeQuery(query);
        CharStream cs = CharStreams.fromString(normalized);
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);
        BIAPIQueryParser.QueryContext tree = parser.query();

        QueryToPredicateJsonListener listener = new QueryToPredicateJsonListener(vars, objectVars, new StringSubstitutor(vars != null ? vars : java.util.Collections.emptyMap()), modelClass);
        ParseTreeWalker.DEFAULT.walk(listener, tree);
        return listener.getPredicate();
    }

    /**
     * Normalizes operators and query macros (@asOf, @minConfidence) prior to parsing.
     * @param query query string to normalize
     * @return normalized query string
     */
    public static String normalizeQuery(String query) {
        if (query == null) return null;
        String normalized = query.replace(":=[", ":^[");

        // Expand edges.@asOf(...) and @asOf(...)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(edges\\.)?@asOf\\s*\\(\\s*([^)]+)\\s*\\)").matcher(normalized);
        if (m.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                boolean hasEdges = m.group(1) != null;
                String arg = m.group(2).trim();
                if ((arg.startsWith("\"") && arg.endsWith("\"")) || (arg.startsWith("'") && arg.endsWith("'"))) {
                    arg = arg.substring(1, arg.length() - 1);
                }
                String prefix = hasEdges ? "edges." : "";
                String rep = "((" + prefix + "validFrom:<=" + arg + " || " + prefix + "validFrom:null) && (" + prefix + "validTo:>=" + arg + " || " + prefix + "validTo:null))";
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
            } while (m.find());
            m.appendTail(sb);
            normalized = sb.toString();
        }

        // Expand edges.@minConfidence(...) and @minConfidence(...)
        java.util.regex.Matcher mc = java.util.regex.Pattern.compile("(edges\\.)?@minConfidence\\s*\\(\\s*([^)]+)\\s*\\)").matcher(normalized);
        if (mc.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                boolean hasEdges = mc.group(1) != null;
                String arg = mc.group(2).trim();
                if ((arg.startsWith("\"") && arg.endsWith("\"")) || (arg.startsWith("'") && arg.endsWith("'"))) {
                    arg = arg.substring(1, arg.length() - 1);
                }
                String prefix = hasEdges ? "edges." : "";
                String rep = prefix + "confidence:>=" + arg;
                mc.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
            } while (mc.find());
            mc.appendTail(sb);
            normalized = sb.toString();
        }

        return normalized;
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
