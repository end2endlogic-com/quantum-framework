package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;

/**
 * Resolves the DataDomain to stamp on a newly created record.
 * Default behavior should always fallback to the principal's credential domain
 * to preserve current behavior when no policy is present.
 */
public interface DataDomainResolver {
    /**
     * Resolve the DataDomain for a create operation.
     * @param functionalArea the model's functional area
     * @param functionalDomain the model's functional domain
     * @return a non-null DataDomain; if no policy applies, returns the principal's domain
     */
    DataDomain resolveForCreate(String functionalArea, String functionalDomain);

    /**
     * Resolve the DataDomain for a create or upsert operation with access to the
     * concrete entity instance being persisted.
     *
     * <p>The default implementation preserves the legacy contract by delegating to
     * {@link #resolveForCreate(String, String)}. Implementations may override this
     * when placement depends on entity contents such as ownership references.</p>
     *
     * @param functionalArea the model's functional area
     * @param functionalDomain the model's functional domain
     * @param entity the concrete entity being persisted
     * @return a non-null DataDomain
     */
    default DataDomain resolveForCreate(String functionalArea, String functionalDomain, Object entity) {
        return resolveForCreate(functionalArea, functionalDomain);
    }

    /**
     * Resolve the DataDomain for a source/ingestion create where there is no authenticated
     * principal and placement must be derived from the ingested row's attribute values plus
     * the source binding (see {@code FROM_SOURCE} resolution mode).
     *
     * <p>The default implementation preserves the legacy contract by ignoring {@code attrs}
     * and delegating to {@link #resolveForCreate(String, String, Object)}, so all existing
     * implementations remain valid without change.</p>
     *
     * @param functionalArea the model's functional area
     * @param functionalDomain the model's functional domain
     * @param entity the concrete entity being persisted (may be null for raw source rows)
     * @param attrs the ingested source-row values and source-binding metadata
     * @return a non-null DataDomain. Implementations that support {@code FROM_SOURCE} may
     *         return a distinguished UNRESOLVABLE sentinel when required components are missing.
     */
    default DataDomain resolveForCreate(String functionalArea, String functionalDomain, Object entity, SourceAttributes attrs) {
        return resolveForCreate(functionalArea, functionalDomain, entity);
    }
}
