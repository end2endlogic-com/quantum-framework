package com.e2eq.framework.grammar.sql;

import com.e2eq.framework.grammar.sql.QueryToSqlListener.SqlWhere;
import com.e2eq.framework.grammar.sql.QueryToSqlListener.UnsupportedQueryException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression suite for the BIAPIQuery → SQL WHERE translator (federation tier 1). Locks in the
 * security-critical behavior: precedence (AND binds tighter than OR), positional-param alignment
 * across composition reordering, IN/NIN, ontology-field → source-column mapping, and fail-closed
 * rejection of anything not yet translatable.
 */
class QueryToSqlListenerTest {

    private static void assertWhere(String query, String expectedWhere, List<Object> expectedParams) {
        SqlWhere w = QueryToSqlListener.translate(query);
        assertEquals(expectedWhere, w.whereClause, "WHERE for: " + query);
        assertEquals(expectedParams, w.params, "params for: " + query);
    }

    // --- scalars -----------------------------------------------------------------

    @Test
    void singlePredicate_noParens() {
        assertWhere("name:Acme", "name = ?", List.of("Acme"));
    }

    @Test
    void comparisonOperators() {
        assertWhere("age:>#30", "age > ?", List.of(30L));
        assertWhere("total:>=##100.50", "total >= ?", List.of(100.50d));
        assertWhere("status:!X", "status <> ?", List.of("X"));
    }

    @Test
    void booleanAndNullAndExists() {
        assertWhere("active:TRUE", "active = ?", List.of(true));
        assertWhere("deletedAt:null", "deletedAt IS NULL", List.of());
        assertWhere("deletedAt:!null", "deletedAt IS NOT NULL", List.of());
        assertWhere("email:~", "email IS NOT NULL", List.of());
    }

    // --- composition + precedence ------------------------------------------------

    @Test
    void conjunction() {
        // popped in reverse; params stay aligned via the named->positional pass
        assertWhere("name:Acme && active:TRUE", "(active = ? AND name = ?)", List.of(true, "Acme"));
    }

    @Test
    void disjunction() {
        assertWhere("a:1 || b:2", "(b = ? OR a = ?)", List.of("2", "1"));
    }

    @Test
    void andBindsTighterThanOr() {
        assertWhere("a:AAA || b:BBB && c:CCC",
                "((c = ? AND b = ?) OR a = ?)", List.of("CCC", "BBB", "AAA"));
        assertWhere("a:AAA && b:BBB || c:CCC",
                "(c = ? OR (b = ? AND a = ?))", List.of("CCC", "BBB", "AAA"));
    }

    // --- IN / NIN ---------------------------------------------------------------

    @Test
    void inAndNotIn() {
        assertWhere("region:^[west,east,south]", "region IN (?, ?, ?)", List.of("west", "east", "south"));
        assertWhere("region:!^[west]", "region NOT IN (?)", List.of("west"));
    }

    @Test
    void inComposesWithScalar_paramsStayAligned() {
        assertWhere("status:ACTIVE && region:^[west,east]",
                "(region IN (?, ?) AND status = ?)", List.of("west", "east", "ACTIVE"));
    }

    // --- ontology-field -> source-column mapping --------------------------------

    @Test
    void fieldToColumnMapping() {
        Map<String, String> m = Map.of("region", "region_code", "tenantId", "tenant_id");
        SqlWhere w = QueryToSqlListener.translate("region:west && tenantId:t1", m);
        assertEquals("(tenant_id = ? AND region_code = ?)", w.whereClause);
        assertEquals(List.of("t1", "west"), w.params);
    }

    @Test
    void unmappedFieldPassesThrough() {
        Map<String, String> m = Map.of("region", "region_code");
        SqlWhere w = QueryToSqlListener.translate("region:west && status:ACTIVE", m);
        assertEquals("(status = ? AND region_code = ?)", w.whereClause);
        assertEquals(List.of("ACTIVE", "west"), w.params);
    }

    @Test
    void unsafeResolvedColumnRejected() {
        assertThrows(UnsupportedQueryException.class,
                () -> QueryToSqlListener.translate("region:west", Map.of("region", "region; DROP TABLE x")));
    }

    // --- explicit grouping (governed composition) --------------------------------

    @Test
    void parenthesizedGroup_overridesPrecedence() {
        // Without parens this is a:1 OR (b:2 AND c:3); the parens force the OR to be grouped
        // and ANDed with c — the exact shape a governed `(userQuery) && (policyFilter)` needs.
        // (Reference AND-ordering emits the right-hand operand first; params stay aligned.)
        assertWhere("(a:1 || b:2) && c:3",
                "(c = ? AND (b = ? OR a = ?))", List.of("3", "2", "1"));
    }

    @Test
    void singleElementGroup_unwraps() {
        assertWhere("(name:Acme)", "name = ?", List.of("Acme"));
    }

    @Test
    void governedComposition_userOrCannotEscapePolicyAnd() {
        // The governance-critical case: a user query with a top-level OR, ANDed with a tenant
        // policy filter. The tenant predicate MUST gate BOTH branches of the OR — and it does:
        // it ANDs with the entire (b OR a) group, not just one branch.
        assertWhere("(a:1 || b:2) && tenantId:t1",
                "(tenantId = ? AND (b = ? OR a = ?))", List.of("t1", "2", "1"));
    }

    @Test
    void nestedGroups() {
        assertWhere("(a:1 || b:2) && (c:3 || d:4)",
                "((d = ? OR c = ?) AND (b = ? OR a = ?))", List.of("4", "3", "2", "1"));
    }

    // --- fail closed -------------------------------------------------------------

    @Test
    void notRejected() {
        assertThrows(UnsupportedQueryException.class, () -> QueryToSqlListener.translate("!!a:1"));
    }

    @Test
    void regexRejected() {
        assertThrows(UnsupportedQueryException.class, () -> QueryToSqlListener.translate("name:*acme*"));
    }

    @Test
    void ontologyEdgeFunctionsRejected() {
        assertThrows(UnsupportedQueryException.class, () -> QueryToSqlListener.translate("hasEdge(assignedTo, t1)"));
        assertThrows(UnsupportedQueryException.class, () -> QueryToSqlListener.translate("expand(orders)"));
    }

    @Test
    void parseErrorFailsClosed() {
        assertThrows(UnsupportedQueryException.class, () -> QueryToSqlListener.translate("this is not && a valid ::: query"));
    }
}
