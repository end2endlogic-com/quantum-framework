package com.e2eq.framework.security.runtime;

import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.filters.LogicalFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Combines security rule filters with caller filters so that neither can replace the other.
 *
 * <p>Morphia's {@code Query.filter(f1, f2, ...)} encodes every top-level filter into ONE query
 * document keyed by field. Two filters on the same field are merged: an equality on a field
 * overwrites any earlier predicate on that field (the last one wins), and two operator documents on
 * the same field are merged key by key. Passing rule filters and caller filters to it as varargs
 * therefore lets a caller filter on a partition field such as {@code dataDomain.tenantId} replace
 * the rule filter on that field, and lets an {@code _id} filter replace the fail-closed
 * {@code _id $in []} match-nothing filter.</p>
 *
 * <p>{@link #conjoin} returns a single explicit {@code $and} whose clauses are encoded as separate
 * documents, so every rule filter and every caller filter constrains the result on its own. Every
 * query that applies rule filters must pass its filters through here instead of handing a
 * {@code Filter[]} built from rule and caller filters to {@code Query.filter(...)}.</p>
 *
 * <p>The result is always wrapped (even a single filter becomes {@code $and:[f]}), so a filter a
 * caller chains onto the query afterwards with another {@code .filter(...)} call lands beside the
 * {@code $and}, never on top of a rule filter's field.</p>
 */
public final class RuleFilterComposition {

    private RuleFilterComposition() {
    }

    /**
     * @param filters rule filters and caller filters, in any order; null entries are ignored
     * @return an empty array when there is nothing to constrain, else exactly one {@code $and} filter
     */
    public static Filter[] conjoin(List<? extends Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return new Filter[0];
        }
        List<Filter> clauses = new ArrayList<>(filters.size());
        for (Filter filter : filters) {
            if (filter != null) {
                clauses.add(filter);
            }
        }
        if (clauses.isEmpty()) {
            return new Filter[0];
        }
        if (clauses.size() == 1 && isAnd(clauses.get(0))) {
            return new Filter[]{clauses.get(0)};
        }
        return new Filter[]{Filters.and(clauses.toArray(new Filter[0]))};
    }

    /** Varargs form of {@link #conjoin(List)}. */
    public static Filter[] conjoin(Filter... filters) {
        return conjoin(filters == null ? List.of() : java.util.Arrays.asList(filters));
    }

    private static boolean isAnd(Filter filter) {
        return filter instanceof LogicalFilter && "$and".equals(filter.getName());
    }
}
