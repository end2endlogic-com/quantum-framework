package com.e2eq.framework.tests.query;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.query.runtime.QueryPredicates;
import com.e2eq.framework.query.runtime.ValidatingQueryToPredicateJsonListener;
import com.fasterxml.jackson.databind.JsonNode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryToPredicateJsonListener via QueryPredicates utility.
 * These tests exercise equality, relational comparisons, IN/NIN, and elemMatch queries
 * against simple JSON-shaped data.
 */
public class QueryToPredicateJsonListenerTest {

    private JsonNode nodeOf(Map<String, Object> map) {
        return QueryPredicates.toJsonNode(map);
    }

    private Predicate<JsonNode> pred(String q) {
        return QueryPredicates.compilePredicate(q, null, null);
    }

    @Test
    void testEqualsOnScalars() {
        Map<String, Object> m = new HashMap<>();
        m.put("field", 123L);
        m.put("name", "Alice");
        m.put("flag", true);
        JsonNode n = nodeOf(m);

        assertTrue(pred("field:#123").test(n));
        assertFalse(pred("field:#122").test(n));

        assertTrue(pred("name:Alice").test(n));
        assertTrue(pred("name:\"Alice\"").test(n));
        assertFalse(pred("name:Bob").test(n));

        assertTrue(pred("flag:TRUE").test(n));
        assertFalse(pred("flag:FALSE").test(n));
    }

    @Test
    void testStringFiltersAreCaseInsensitiveByDefault() {
        JsonNode n = nodeOf(Map.of("name", "Alice"));

        assertTrue(pred("name:alice").test(n));
        assertTrue(pred("name:*lic*").test(n));
        assertTrue(pred("name:*LIC*").test(n));
    }

    @Test
    void testStringFiltersCanOptIntoCaseSensitiveMatching() {
        JsonNode n = nodeOf(Map.of("name", "Alice"));

        assertFalse(pred("name:alice~cs").test(n));
        assertFalse(pred("name:*LIC*~cs").test(n));
        assertTrue(pred("name:Alice~cs").test(n));
        assertTrue(pred("name:*lic*~cs").test(n));
    }

    @Test
    void testRelationalComparisons() {
        Map<String, Object> m = Map.of("num", 10L, "price", 12.5);
        JsonNode n = nodeOf(m);

        assertTrue(pred("num:>#5").test(n));
        assertTrue(pred("num:>=#10").test(n));
        assertFalse(pred("num:<#10").test(n));
        assertTrue(pred("price:>##10.0").test(n));
        assertTrue(pred("price:<=##12.5").test(n));
        assertFalse(pred("price:<##12.5").test(n));
    }

    @Test
    void testInAndNotInOnScalarField() {
        Map<String, Object> m = Map.of("color", "red", "id", "66d1f1ab452b94674bbd934a");
        JsonNode n = nodeOf(m);

        assertTrue(pred("color:^[red,blue]").test(n));
        assertFalse(pred("color:^[green,blue]").test(n));

        // ObjectId style (24 hex chars) should coerce
        assertTrue(pred("id:^[66d1f1ab452b94674bbd934a,66d1f1ab452b94674bbd934b]").test(n));
        assertFalse(pred("id:!^[66d1f1ab452b94674bbd934a,66d1f1ab452b94674bbd934b]").test(n));
    }

    @Test
    void testInOnArrayField() {
        Map<String, Object> m = Map.of(
                "tags", List.of("alpha", "beta", "gamma"),
                "nums", List.of(1, 2, 3, 4)
        );
        JsonNode n = nodeOf(m);

        assertTrue(pred("tags:^[delta,beta]").test(n)); // any element matches
        assertFalse(pred("tags:^[delta,epsilon]").test(n));

        assertTrue(pred("nums:^[10,3]").test(n));
        assertFalse(pred("nums:^[10,30]").test(n));
    }

    @Test
    void testElemMatchSimpleAndOr() {
        Map<String, Object> item1 = Map.of("sub", 1L, "other", 2L);
        Map<String, Object> item2 = Map.of("sub", 20L, "other", 5L);
        Map<String, Object> m = Map.of("arrayField", List.of(item1, item2));
        JsonNode n = nodeOf(m);

        // arrayField:{subField:1&&otherSub:2}
        assertTrue(pred("arrayField:{sub:#1&&other:#2}").test(n));
        assertFalse(pred("arrayField:{sub:#2&&other:#2}").test(n));

        // arrayField:{(subField:<#12)||(subField:>#15)}
        assertTrue(pred("arrayField:{(sub:<#12)||(sub:>#15)}").test(n));
        assertFalse(pred("arrayField:{(sub:<#0)||(sub:>#100)}").test(n));
    }

    @Test
    void testNestedFieldComparisons() {
        Map<String, Object> m = Map.of(
                "user", Map.of(
                        "address", Map.of(
                                "city", "Paris",
                                "zip", 75000
                        )
                )
        );
        JsonNode n = nodeOf(m);

        assertTrue(pred("user.address.city:Paris").test(n));
        assertTrue(pred("user.address.zip:>#70000").test(n));
        assertFalse(pred("user.address.city:London").test(n));
    }

    @Test
    void testVariablesInInList() {
        Map<String, String> vars = Map.of("list", "a,b,c");
        Map<String, Object> obj = Map.of("vals", Set.of("x", "y"));
        Map<String, Object> m = Map.of("k", "b", "arr", List.of("y", "z"));
        JsonNode n = nodeOf(m);

        // Single variable expansion inside brackets
        Predicate<JsonNode> p1 = QueryPredicates.compilePredicate("k:^[$%7Blist%7D]".replace("%7B", "{").replace("%7D", "}"), vars, null);
        assertTrue(p1.test(n));

        // Object variable collection expansion
        Predicate<JsonNode> p2 = QueryPredicates.compilePredicate("arr:^[$%7Bvals%7D]".replace("%7B", "{").replace("%7D", "}"), null, obj);
        assertTrue(p2.test(n));
    }

    @Test
    void testTextSearchMatchesAnyTokenCaseInsensitive() {
        Map<String, Object> m = Map.of(
                "title", "Priority Escalation Runbook",
                "description", "Escalate to on-call"
        );
        JsonNode n = nodeOf(m);

        assertTrue(pred("text(\"priority escalation\")").test(n));
        assertTrue(pred("text(\"ESCALATION\")").test(n));
        assertFalse(pred("text(\"unrelated\")").test(n));
    }

    @Test
    void testTextSearchUsesWordBoundariesNotSubstrings() {
        // MongoDB $text is word-oriented; in-memory must not match substrings.
        JsonNode category = nodeOf(Map.of("title", "category"));
        JsonNode cat = nodeOf(Map.of("title", "cat"));
        JsonNode catInSentence = nodeOf(Map.of("title", "the cat sat"));

        assertFalse(pred("text(\"cat\")").test(category));
        assertTrue(pred("text(\"cat\")").test(cat));
        assertTrue(pred("text(\"cat\")").test(catInSentence));
    }

    @Test
    void testTextSearchHyphenatedTermMatchesHyphenatedFieldValue() {
        // Query and document must share the same non-letter/non-digit tokenizer.
        JsonNode hyphenated = nodeOf(Map.of("title", "foo-bar"));
        assertTrue(pred("text(\"foo-bar\")").test(hyphenated));
        assertTrue(pred("text(\"foo\")").test(hyphenated));
        assertTrue(pred("text(\"bar\")").test(hyphenated));
    }

    @Test
    void testTextKeywordAsFieldAndValueStillParses() {
        // TEXT is a lexer keyword for text(...), but "text" remains a valid
        // unquoted field name / value so existing queries keep working.
        JsonNode n = nodeOf(Map.of("type", "text", "text", "active"));

        assertTrue(pred("type:text").test(n));
        assertTrue(pred("text:active").test(n));
        assertTrue(pred("type:^ [text]").test(n));
        assertFalse(pred("type:plain").test(n));
    }

    @Test
    void testTextSearchCombinedWithOtherFilters() {
        Map<String, Object> m = Map.of(
                "title", "Priority Escalation Runbook",
                "status", "OPEN"
        );
        JsonNode n = nodeOf(m);

        assertTrue(pred("text(\"priority escalation\")&&status:OPEN").test(n));
        assertFalse(pred("text(\"priority escalation\")&&status:CLOSED").test(n));
        // Valid: text at top level AND'd with an OR group of non-text predicates
        assertTrue(pred("text(\"priority\")&&(status:OPEN||status:CLOSED)").test(n));
    }

    @Test
    void testDuplicateTextSearchThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> pred("text(\"one\")&&text(\"two\")"));
        assertTrue(ex.getMessage().toLowerCase().contains("multiple"));
    }

    @Test
    void testTextInsideNotThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> pred("!!text(\"foo\")"));
        assertTrue(ex.getMessage().contains("text(...)"));
        assertTrue(ex.getMessage().contains("NOT") || ex.getMessage().toLowerCase().contains("negat"));
    }

    @Test
    void testTextInsideOrThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> pred("text(\"foo\")||status:OPEN"));
        assertTrue(ex.getMessage().toLowerCase().contains("or"));
    }

    @Test
    void testTextInsideElemMatchThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> pred("tags:{text(\"foo\")}"));
        assertTrue(ex.getMessage().toLowerCase().contains("elemmatch"));
    }

    @Test
    void testEmptyTextSearchThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> pred("text(\"\")"));
        assertTrue(ex.getMessage().toLowerCase().contains("empty") || ex.getMessage().contains("non-empty"));
    }

    @Test
    void testExpandExpressionEvaluatesAsPassThrough() {
        JsonNode openNode = nodeOf(Map.of("id", "ord-1", "status", "OPEN", "price", 10.0));
        JsonNode closedNode = nodeOf(Map.of("id", "ord-2", "status", "CLOSED", "price", 10.0));

        // Solo expand expression returns true (pass-through)
        assertTrue(pred("expand(orderRef)").test(openNode));
        assertTrue(pred("expand(orderRef)").test(closedNode));

        // expand(...) && condition evaluates the condition
        assertTrue(pred("expand(orderRef)&&status:OPEN").test(openNode));
        assertFalse(pred("expand(orderRef)&&status:OPEN").test(closedNode));

        // condition && expand(...) evaluates the condition
        assertTrue(pred("status:OPEN&&expand(orderRef)").test(openNode));
        assertFalse(pred("status:OPEN&&expand(orderRef)").test(closedNode));

        // Complex expand path with wildcard
        assertTrue(pred("expand(items[*].product)&&price:>#5").test(openNode));
    }

    @Test
    void testHasEdgeFailsClosedWhenRepoUnavailable() {
        JsonNode node = nodeOf(Map.of("id", "usr-1", "status", "OPEN"));

        // Fails closed (returns false) without throwing
        assertFalse(pred("hasEdge(\"placedInOrg\", \"OrgA\")").test(node));
        assertFalse(pred("hasOutgoingEdge(\"placedInOrg\", \"OrgA\")").test(node));
        assertFalse(pred("hasIncomingEdge(\"canSeeLocation\", \"Loc1\")").test(node));

        // Compound query with hasEdge fails closed
        assertFalse(pred("hasEdge(\"placedInOrg\", \"OrgA\")&&status:OPEN").test(node));
    }

    @Test
    void testHasEdgeWithEdgePropertyFilter() {
        JsonNode node = nodeOf(Map.of("id", "usr-1", "status", "OPEN"));

        // With edge property filter block - verifies stack markers, composite scoping, and fail-closed behavior
        assertFalse(pred("hasEdge(\"placedInOrg\", \"OrgA\", { role:\"PRIMARY\" && status:\"ACTIVE\" })").test(node));
        assertFalse(pred("hasOutgoingEdge(\"placedInOrg\", \"OrgA\", { role:\"PRIMARY\" })&&status:OPEN").test(node));
        assertFalse(pred("hasIncomingEdge(\"canSeeLocation\", \"Loc1\", { permission:\"READ\" })").test(node));
    }

    @Test
    void testValidatingListenerWithEdgeFilterDoesNotRejectDynamicEdgeProperties() {
        Set<String> validModelFields = Set.of("id", "status", "name");

        // 1. Dynamic edge properties (role, edgeWeight) inside { edgeFilter } are NOT rejected
        String validQuery = "hasEdge(\"placedInOrg\", \"OrgA\", { role:\"PRIMARY\" && edgeWeight:>#5 })&&status:OPEN";
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(validQuery));
        BIAPIQueryParser parser = new BIAPIQueryParser(new CommonTokenStream(lexer));
        ValidatingQueryToPredicateJsonListener listener = new ValidatingQueryToPredicateJsonListener(validModelFields);
        ParseTreeWalker.DEFAULT.walk(listener, parser.query());

        assertFalse(listener.hasValidationErrors(), "Dynamic edge properties should not trigger validation errors");

        // 2. An invalid field on the root model outside { edgeFilter } IS rejected
        String invalidQuery = "hasEdge(\"placedInOrg\", \"OrgA\", { role:\"PRIMARY\" })&&unknownField:foo";
        BIAPIQueryLexer lexer2 = new BIAPIQueryLexer(CharStreams.fromString(invalidQuery));
        BIAPIQueryParser parser2 = new BIAPIQueryParser(new CommonTokenStream(lexer2));
        ValidatingQueryToPredicateJsonListener listener2 = new ValidatingQueryToPredicateJsonListener(validModelFields);
        ParseTreeWalker.DEFAULT.walk(listener2, parser2.query());

        assertTrue(listener2.hasValidationErrors(), "Unknown root model field should trigger validation error");
        assertTrue(listener2.getValidationErrors().stream().anyMatch(e -> e.contains("unknownField")));
    }

    @Test
    void testQueryMacrosAndNamespacedVariablesInMemory() throws Exception {
        // Node with edge metadata fields
        String json = """
            {
                "status": "ACTIVE",
                "tier": "GOLD",
                "confidence": 0.92,
                "validFrom": "2026-01-01T00:00:00Z",
                "validTo": "2026-12-31T23:59:59Z"
            }
            """;
        JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);

        // 1. Dotted variable and bracketless IN expansion
        Map<String, String> vars = Map.of("facet.tier", "GOLD");
        Map<String, Object> objVars = Map.of("facet.allowedTiers", List.of("SILVER", "GOLD"));
        Predicate<JsonNode> p1 = QueryPredicates.compilePredicate("tier:^${facet.allowedTiers}", vars, objVars);
        assertTrue(p1.test(node), "Should match tier via ${facet.allowedTiers}");

        // 2. Query macros: @asOf and @minConfidence
        Predicate<JsonNode> p2 = QueryPredicates.compilePredicate("@asOf(2026-06-15T12:00:00Z) && @minConfidence(0.85)", vars, objVars);
        assertTrue(p2.test(node), "Should match @asOf inside validity range and above min confidence");

        // 3. Negative check: @minConfidence higher than actual
        Predicate<JsonNode> p3 = QueryPredicates.compilePredicate("@minConfidence(0.95)", vars, objVars);
        assertFalse(p3.test(node), "Should not match when confidence is below required threshold");

        // 4. Negative check: @asOf outside validity range
        Predicate<JsonNode> p4 = QueryPredicates.compilePredicate("@asOf(2027-01-01T00:00:00Z)", vars, objVars);
        assertFalse(p4.test(node), "Should not match when asOf date is after validTo");
    }
}
