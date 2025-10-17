package com.e2eq.framework.query;

import com.fasterxml.jackson.databind.JsonNode;
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
}
