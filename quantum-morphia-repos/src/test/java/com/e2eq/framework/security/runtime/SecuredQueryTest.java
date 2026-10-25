package com.e2eq.framework.security.runtime;

import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.filters.LogicalFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SecuredQuery} keeps the clause list itself: every {@code filter(...)} rebuilds a fresh
 * Morphia query with exactly one {@code $and} whose first clause is the secured filter, so nothing
 * a caller chains ever reaches Morphia as a separate top-level filter.
 */
class SecuredQueryTest {

    /** One fresh stub query per rebuild; records the filter(...) and validation calls it receives. */
    private static final class Recorder {
        final List<List<Filter>> filterCalls = new ArrayList<>();
        final List<String> validationCalls = new ArrayList<>();
        int created;

        @SuppressWarnings("unchecked")
        Query<Object> newQuery() {
            created++;
            List<Filter> calls = new ArrayList<>();
            int id = created;
            filterCalls.add(calls);
            Object[] self = new Object[1];
            self[0] = Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[]{Query.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "filter" -> {
                            calls.addAll(Arrays.asList((Filter[]) args[0]));
                            yield proxy;
                        }
                        case "disableValidation", "enableValidation" -> {
                            validationCalls.add(created + ":" + method.getName());
                            yield proxy;
                        }
                        case "count" -> 0L;
                        case "first" -> null;
                        case "getLoggedQuery" -> "query-" + id;
                        case "toString" -> "stub";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            return (Query<Object>) self[0];
        }

        List<Filter> lastFilters() {
            return filterCalls.get(filterCalls.size() - 1);
        }
    }

    @Test
    void securedClauseAloneIsTheOnlyTopLevelFilter() {
        Recorder r = new Recorder();
        Filter secured = Filters.and(Filters.eq("dataDomain.ownerId", "me"));

        new SecuredQuery<>(r::newQuery, new Filter[]{secured});

        assertEquals(List.of(secured), r.lastFilters(), "an existing $and is passed through unwrapped");
    }

    @Test
    void everyChainedFilterIsRebuiltIntoOneAndBehindTheSecuredClause() {
        Recorder r = new Recorder();
        Filter secured = Filters.and(Filters.eq("dataDomain.ownerId", "me"));
        Filter callerAnd = Filters.and(Filters.eq("dataDomain.ownerId", "other"), Filters.eq("refName", "x"));
        Filter callerOr = Filters.or(Filters.eq("dataDomain.ownerId", "other"));
        Filter callerEq = Filters.eq("dataDomain.ownerId", "other");

        SecuredQuery<Object> q = new SecuredQuery<>(r::newQuery, new Filter[]{secured});
        assertSame(q, q.filter(callerAnd));
        q.filter(callerOr).filter(callerEq);

        assertEquals(4, r.created, "a fresh Morphia query per filter(...) call");
        assertEquals(1, r.lastFilters().size(), "exactly one top-level filter reaches Morphia");
        LogicalFilter and = (LogicalFilter) r.lastFilters().get(0);
        assertEquals("$and", and.getName());
        assertEquals(List.of(secured, callerAnd, callerOr, callerEq), and.filters());
        assertEquals(4, q.clauseCount());
    }

    @Test
    void validationCannotBeToggledBecauseItWouldReachTheSecuredClause() {
        Recorder r = new Recorder();
        SecuredQuery<Object> q = new SecuredQuery<>(r::newQuery, new Filter[]{Filters.eq("a", 1)});

        assertThrows(UnsupportedOperationException.class, q::disableValidation);
        assertThrows(UnsupportedOperationException.class, q::enableValidation);
        assertEquals(List.of(), r.validationCalls);
    }

    @Test
    void loggedQueryIsTheLastExecutedOneEvenAfterMoreFilters() {
        Recorder r = new Recorder();
        SecuredQuery<Object> q = new SecuredQuery<>(r::newQuery, new Filter[]{Filters.eq("a", 1)});

        q.first();
        q.filter(Filters.eq("b", 2));

        assertEquals("query-1", q.getLoggedQuery(), "the query that ran, not the rebuilt one");
        q.count();
        assertEquals("query-2", q.getLoggedQuery());
    }

    @Test
    void noSecuredFiltersAndNoCallerFiltersAppliesNothing() {
        Recorder r = new Recorder();
        SecuredQuery<Object> q = new SecuredQuery<>(r::newQuery, new Filter[0]);

        assertEquals(List.of(), r.lastFilters());
        assertEquals(0L, q.count());
    }
}
