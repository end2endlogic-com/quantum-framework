package com.e2eq.framework.grammar.sql;

import com.e2eq.framework.grammar.sql.QueryToSqlListener.SqlWhere;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A parenthesised group composes only what is inside it. The rendered WHERE clause is evaluated
 * with SQL precedence (AND before OR) over every combination of four fields.
 */
class QueryToSqlListenerGroupScopeTest {

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
    void whereClauseMeansWhatTheQuerySays(String query, Truth truth) {
        SqlWhere where = QueryToSqlListener.translate(query);
        for (int bits = 0; bits < 16; bits++) {
            Map<String, Object> r = record(bits);
            assertEquals(expected(truth, bits), new Eval(where, r).run(), query + " on " + r + " as " + where.whereClause);
        }
    }

    /** Evaluates the translator's output: equality atoms joined by AND/OR with parentheses. */
    private static final class Eval {
        private static final Pattern TOKEN = Pattern.compile("\\(|\\)|AND|OR|[A-Za-z_][A-Za-z0-9_.]* = \\?");
        private final List<String> tokens = new ArrayList<>();
        private final Iterator<Object> params;
        private final Map<String, Object> r;
        private int pos;

        Eval(SqlWhere where, Map<String, Object> r) {
            Matcher m = TOKEN.matcher(where.whereClause);
            int end = 0;
            while (m.find()) {
                assertEquals("", where.whereClause.substring(end, m.start()).trim(), "unexpected SQL text");
                tokens.add(m.group());
                end = m.end();
            }
            assertEquals("", where.whereClause.substring(end).trim(), "unexpected SQL text");
            this.params = where.params.iterator();
            this.r = r;
        }

        boolean run() {
            boolean v = or();
            assertEquals(tokens.size(), pos, "unconsumed SQL tokens");
            assertEquals(false, params.hasNext(), "unused parameters");
            return v;
        }

        private boolean or() {
            boolean v = and();
            while (pos < tokens.size() && tokens.get(pos).equals("OR")) { pos++; boolean w = and(); v = v || w; }
            return v;
        }

        private boolean and() {
            boolean v = atom();
            while (pos < tokens.size() && tokens.get(pos).equals("AND")) { pos++; boolean w = atom(); v = v && w; }
            return v;
        }

        private boolean atom() {
            String t = tokens.get(pos++);
            if (t.equals("(")) {
                boolean v = or();
                assertEquals(")", tokens.get(pos++));
                return v;
            }
            String column = t.substring(0, t.indexOf(' '));
            return Objects.equals(r.get(column), params.next());
        }
    }
}
