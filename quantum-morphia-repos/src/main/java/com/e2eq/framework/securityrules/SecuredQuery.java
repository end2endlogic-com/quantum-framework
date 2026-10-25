package com.e2eq.framework.security.runtime;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.lang.Nullable;
import dev.morphia.DeleteOptions;
import dev.morphia.ModifyOptions;
import dev.morphia.UpdateOptions;
import dev.morphia.aggregation.stages.Stage;
import dev.morphia.query.CountOptions;
import dev.morphia.query.FindAndDeleteOptions;
import dev.morphia.query.FindOptions;
import dev.morphia.query.MorphiaCursor;
import dev.morphia.query.MorphiaQuery;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.updates.UpdateOperator;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A Morphia {@link Query} whose security rule filters cannot be replaced by anything a caller adds.
 *
 * <p>Morphia's {@code Query.filter(...)} is cumulative: every call adds top-level filters, and when
 * the query document is encoded, filters that land on the same top-level key are merged by
 * Morphia's internal document writer. How that merge treats a repeated key ({@code $and},
 * {@code $or}, or a field) is a Morphia implementation detail that differs between versions. A
 * secured query must not depend on it.</p>
 *
 * <p>This query keeps the clause list itself. The first clause is the secured filter (the rule
 * filters and the caller filters known when the query was opened, already one {@code $and} from
 * {@link RuleFilterComposition#conjoin}); every later {@link #filter} call appends its filters as
 * further clauses and rebuilds the underlying Morphia query from scratch with
 * {@code filter(Filters.and(secured, added...))}. The encoded query document therefore always has
 * exactly one top-level {@code $and} (plus Morphia's discriminator), and the secured clause is one
 * of its elements, so no caller filter, compound or not, can share a key with it.</p>
 *
 * <p>Obtain one from {@code MorphiaRepo.securedQuery(...)}; do not chain filters onto a raw
 * {@code datastore.find(...).filter(getFilterArray(...))} query.</p>
 *
 * @param <T> the entity type
 */
public final class SecuredQuery<T> implements Query<T> {

    private final Supplier<Query<T>> unfiltered;
    private final List<Filter> clauses = new ArrayList<>();
    private Boolean validation;
    private Query<T> delegate;

    /**
     * @param unfiltered creates a fresh query with no filters (e.g. {@code () -> ds.find(type)})
     * @param secured    the secured filter array from {@code getFilterArray(...)}; it must not be
     *                   empty when rules apply, and it is kept as the first clause
     */
    public SecuredQuery(Supplier<Query<T>> unfiltered, Filter[] secured) {
        this.unfiltered = Objects.requireNonNull(unfiltered, "unfiltered");
        if (secured != null) {
            for (Filter f : secured) {
                if (f != null) {
                    clauses.add(f);
                }
            }
        }
        rebuild();
    }

    private void rebuild() {
        Query<T> q = unfiltered.get();
        if (Boolean.FALSE.equals(validation)) {
            q = q.disableValidation();
        } else if (Boolean.TRUE.equals(validation)) {
            q = q.enableValidation();
        }
        Filter[] conjoined = RuleFilterComposition.conjoin(clauses);
        if (conjoined.length > 0) {
            q = q.filter(conjoined);
        }
        delegate = q;
    }

    /** The clauses of the single top-level {@code $and}, secured clause first. */
    public List<Filter> clauses() {
        return Collections.unmodifiableList(clauses);
    }

    /**
     * Adds filters as further clauses of the one top-level {@code $and}; they narrow the result and
     * can never replace or merge into the secured clause.
     */
    @Override
    public SecuredQuery<T> filter(Filter... filters) {
        if (filters != null) {
            for (Filter f : filters) {
                if (f != null) {
                    clauses.add(f);
                }
            }
        }
        rebuild();
        return this;
    }

    @Override
    public SecuredQuery<T> disableValidation() {
        validation = Boolean.FALSE;
        delegate = delegate.disableValidation();
        return this;
    }

    @Override
    public SecuredQuery<T> enableValidation() {
        validation = Boolean.TRUE;
        delegate = delegate.enableValidation();
        return this;
    }

    @Override
    public long count() {
        return delegate.count();
    }

    @Override
    public long count(CountOptions options) {
        return delegate.count(options);
    }

    @Override
    public DeleteResult delete(DeleteOptions options) {
        return delegate.delete(options);
    }

    @Override
    public Map<String, Object> explain(FindOptions options, @Nullable ExplainVerbosity verbosity) {
        return delegate.explain(options, verbosity);
    }

    @Override
    public String getLoggedQuery() {
        return delegate.getLoggedQuery();
    }

    @Override
    public T findAndDelete(FindAndDeleteOptions options) {
        return delegate.findAndDelete(options);
    }

    @Override
    public T first() {
        return delegate.first();
    }

    @Override
    public T first(FindOptions options) {
        return delegate.first(options);
    }

    @Override
    public T modify(ModifyOptions options, UpdateOperator first, UpdateOperator... updates) {
        return delegate.modify(options, first, updates);
    }

    @Override
    public MorphiaCursor<T> iterator(@Nullable FindOptions options) {
        return delegate.iterator(options);
    }

    @Override
    public UpdateResult update(UpdateOptions options, UpdateOperator first, UpdateOperator... updates) {
        return delegate.update(options, first, updates);
    }

    @Override
    public UpdateResult update(UpdateOptions options, Stage first, Stage... updates) {
        return delegate.update(options, first, updates);
    }

    /** The encoded query document, for diagnostics and tests. */
    public Document toDocument() {
        if (delegate instanceof MorphiaQuery<T> mq) {
            return mq.toDocument();
        }
        throw new UnsupportedOperationException("underlying query cannot be encoded: " + delegate.getClass());
    }

    @Override
    public String toString() {
        return "SecuredQuery" + clauses;
    }
}
