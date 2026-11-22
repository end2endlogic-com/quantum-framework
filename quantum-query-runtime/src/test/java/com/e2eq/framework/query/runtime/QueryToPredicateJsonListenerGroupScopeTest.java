package com.e2eq.framework.tests.query;

import com.e2eq.framework.query.runtime.QueryPredicates;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A parenthesised group composes only what is inside it, for the in-memory predicate. */
class QueryToPredicateJsonListenerGroupScopeTest {

    /** The expected truth value of a case over the four boolean fields. */
    @FunctionalInterface
    interface Truth { boolean test(boolean a, boolean b, boolean c, boolean d); }

    /**
     * Grouped and mixed-precedence queries, with their meaning written out by hand. AND binds
     * tighter than OR, and a parenthesised group is one operand. The first case is the composed
     * governance shape {@code (rule1) && (rule2)} whose nested group used to drop {@code a}.
     */
    static Stream<Arguments> groupedQueries() {
        return Stream.of(
            Arguments.of("(a:Ta) && (b:Tb || (c:Tc) || d:Td)", (Truth) (a, b, c, d) -> a && (b || c || d)),
            Arguments.of("a:Ta && (b:Tb || (c:Tc) || d:Td)", (Truth) (a, b, c, d) -> a && (b || c || d)),
            Arguments.of("(a:Ta) && (b:Tb || c:Tc)", (Truth) (a, b, c, d) -> a && (b || c)),
            Arguments.of("((a:Ta) || (b:Tb)) && (c:Tc)", (Truth) (a, b, c, d) -> (a || b) && c),
            Arguments.of("(((a:Ta) || (b:Tb)) && c:Tc)", (Truth) (a, b, c, d) -> (a || b) && c),
            Arguments.of("a:Ta && (b:Tb || (c:Tc || d:Td))", (Truth) (a, b, c, d) -> a && (b || c || d)),
            Arguments.of("((a:Ta || b:Tb)) && ((c:Tc || d:Td))", (Truth) (a, b, c, d) -> (a || b) && (c || d)),
            Arguments.of("(a:Ta || b:Tb) && (c:Tc || d:Td)", (Truth) (a, b, c, d) -> (a || b) && (c || d)),
            Arguments.of("a:Ta && b:Tb || c:Tc", (Truth) (a, b, c, d) -> (a && b) || c),
            Arguments.of("a:Ta || b:Tb && c:Tc", (Truth) (a, b, c, d) -> a || (b && c)),
            Arguments.of("a:Ta || (b:Tb) && c:Tc", (Truth) (a, b, c, d) -> a || (b && c)),
            Arguments.of("(a:Ta) && (b:Tb || (c:Tc) && d:Td || a:Ta)", (Truth) (a, b, c, d) -> a && (b || (c && d) || a)),
            Arguments.of("((a:Ta && (b:Tb)))", (Truth) (a, b, c, d) -> a && b),
            Arguments.of("(a:Ta || (b:Tb && (c:Tc || d:Td))) && d:Td", (Truth) (a, b, c, d) -> (a || (b && (c || d))) && d),
            Arguments.of("a:Ta && (b:Tb && (c:Tc || (d:Td)))", (Truth) (a, b, c, d) -> a && b && (c || d)),
            Arguments.of("(a:Ta) || (b:Tb) && (c:Tc)", (Truth) (a, b, c, d) -> a || (b && c)),
            Arguments.of("((a:Ta) || (b:Tb)) || ((c:Tc) && (d:Td))", (Truth) (a, b, c, d) -> a || b || (c && d)),
            Arguments.of("(a:Ta)", (Truth) (a, b, c, d) -> a)
        );
    }

    private static Map<String, Object> record(int bits) {
        Map<String, Object> r = new HashMap<>();
        String[] fields = {"a", "b", "c", "d"};
        // Each field has its own matching value, so a parameter bound to the wrong column fails.
        for (int i = 0; i < 4; i++) r.put(fields[i], (bits & (1 << i)) != 0 ? "T" + fields[i] : "F");
        return r;
    }

    private static boolean expected(Truth truth, int bits) {
        return truth.test((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groupedQueries")
    void predicateMeansWhatTheQuerySays(String query, Truth truth) {
        Predicate<JsonNode> predicate = QueryPredicates.compilePredicate(query, null, null);
        for (int bits = 0; bits < 16; bits++) {
            Map<String, Object> r = record(bits);
            assertEquals(expected(truth, bits), predicate.test(QueryPredicates.toJsonNode(r)), query + " on " + r);
        }
    }
}
