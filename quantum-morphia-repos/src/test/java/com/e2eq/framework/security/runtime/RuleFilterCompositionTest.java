package com.e2eq.framework.security.runtime;

import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.filters.LogicalFilter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule filters and caller filters are combined as separate {@code $and} clauses, never as Morphia
 * varargs (which merges same-field filters, last one wins).
 */
class RuleFilterCompositionTest {

    @Test
    void nothingToConstrainYieldsEmptyArray() {
        assertEquals(0, RuleFilterComposition.conjoin(new ArrayList<>()).length);
        assertEquals(0, RuleFilterComposition.conjoin((List<Filter>) null).length);
        assertEquals(0, RuleFilterComposition.conjoin(Arrays.asList((Filter) null)).length);
    }

    @Test
    void sameFieldFiltersStaySeparateAndClauses() {
        Filter rule = Filters.eq("dataDomain.tenantId", "tenant-a");
        Filter caller = Filters.eq("dataDomain.tenantId", "tenant-b");

        Filter[] out = RuleFilterComposition.conjoin(List.of(caller, rule));

        assertEquals(1, out.length, "one $and, so Morphia has nothing to merge at the top level");
        LogicalFilter and = (LogicalFilter) out[0];
        assertEquals("$and", and.getName());
        assertEquals(List.of(caller, rule), and.filters(), "every clause survives, in order");
    }

    @Test
    void failClosedMatchNothingIsKeptBesideAnIdFilter() {
        Filter matchNothing = RuleFilterVariables.matchNothing();
        Filter byId = Filters.eq("_id", "x");

        LogicalFilter and = (LogicalFilter) RuleFilterComposition.conjoin(List.of(byId, matchNothing))[0];

        assertTrue(and.filters().contains(matchNothing));
        assertTrue(and.filters().contains(byId));
    }

    @Test
    void singleFilterIsStillWrappedSoLaterChainedFiltersCannotOverrideIt() {
        Filter rule = Filters.eq("ownerId", "me");

        Filter[] out = RuleFilterComposition.conjoin(rule);

        assertEquals("$and", out[0].getName());
        assertEquals(List.of(rule), ((LogicalFilter) out[0]).filters());
    }

    @Test
    void anExistingAndIsNotDoubleWrapped() {
        Filter and = Filters.and(Filters.eq("a", 1), Filters.eq("b", 2));
        assertSame(and, RuleFilterComposition.conjoin(and)[0]);
    }
}
