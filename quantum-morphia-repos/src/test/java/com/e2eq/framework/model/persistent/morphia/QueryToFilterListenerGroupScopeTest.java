package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.LogicalFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A parenthesised group composes only what is inside it. The lowered Mongo filter is evaluated
 * over every combination of four fields and must agree with the query's meaning.
 */
class QueryToFilterListenerGroupScopeTest {

    public static class DummyModel extends UnversionedBaseModel {
        @Override public String bmFunctionalArea() { return "test-area"; }
        @Override public String bmFunctionalDomain() { return "test-domain"; }
    }

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
    void loweredFilterMeansWhatTheQuerySays(String query, Truth truth) {
        // ~cs makes each leaf a plain $eq (an unquoted string otherwise lowers to a case-insensitive regex).
        Filter filter = MorphiaUtils.convertToFilter(query.replaceAll("(:T[abcd])", "$1~cs"), DummyModel.class);
        for (int bits = 0; bits < 16; bits++) {
            Map<String, Object> r = record(bits);
            assertEquals(expected(truth, bits), matches(filter, r), query + " on " + r + " as " + filter);
        }
    }

    /** A group inside elemMatch scopes to the element; the group around it scopes to the record. */
    @org.junit.jupiter.api.Test
    void groupsInsideElemMatchScopeToTheElement() {
        Filter filter = MorphiaUtils.convertToFilter(
                "(a:Ta~cs) && items:{(b:Tb~cs) && (c:Tc~cs || d:Td~cs)} || d:Td~cs", DummyModel.class);
        for (int bits = 0; bits < 16; bits++) {
            boolean a = (bits & 1) != 0, b = (bits & 2) != 0, c = (bits & 4) != 0, d = (bits & 8) != 0;
            Map<String, Object> element = new HashMap<>();
            element.put("b", b ? "Tb" : "F");
            element.put("c", c ? "Tc" : "F");
            element.put("d", d ? "Td" : "F");
            Map<String, Object> r = new HashMap<>();
            r.put("a", a ? "Ta" : "F");
            r.put("d", "F");
            r.put("items", java.util.List.of(Map.of("b", "F", "c", "F", "d", "F"), element));
            boolean expected = a && b && (c || d);
            assertEquals(expected, matches(filter, r), r + " as " + filter);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean matches(Filter filter, Map<String, Object> r) {
        if ("$elemMatch".equals(filter.getName())) {
            Object v = filter.getValue();
            java.util.List<Filter> inner = v instanceof Filter f ? java.util.List.of(f)
                    : v instanceof Filter[] arr ? java.util.List.of(arr) : (java.util.List<Filter>) v;
            Object items = r.get(filter.getField());
            return filter.isNot() != (items instanceof java.util.List<?> list && list.stream()
                    .anyMatch(e -> inner.stream().allMatch(f -> matches(f, (Map<String, Object>) e))));
        }
        boolean result;
        if (filter instanceof LogicalFilter logical) {
            result = switch (filter.getName()) {
                case "$and" -> logical.filters().stream().allMatch(f -> matches(f, r));
                case "$or" -> logical.filters().stream().anyMatch(f -> matches(f, r));
                default -> throw new AssertionError("unexpected logical filter " + filter);
            };
        } else {
            Object actual = r.get(filter.getField());
            result = switch (filter.getName()) {
                case "$eq" -> Objects.equals(actual, filter.getValue());
                case "$ne" -> !Objects.equals(actual, filter.getValue());
                default -> throw new AssertionError("unexpected filter " + filter);
            };
        }
        return filter.isNot() != result;
    }
}
