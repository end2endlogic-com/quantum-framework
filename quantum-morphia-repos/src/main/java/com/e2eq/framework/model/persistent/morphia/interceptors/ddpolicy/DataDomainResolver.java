package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainPolicy;

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

    /**
     * Governed ingest resolution (S3). Resolves a single ingested source row to an explicit,
     * fail-closed {@link DataDomainResolution} given an EXPLICIT {@code policy} and the row's
     * {@link SourceAttributes}.
     *
     * <p>Unlike {@link #resolveForCreate(String, String, Object, SourceAttributes)} this entry does
     * NOT read the ambient {@link com.e2eq.framework.model.securityrules.SecurityContext} /
     * principal and never falls back to a principal or default placement: the caller supplies the
     * source's policy directly (the source-bound synthetic principal is deferred to S4). When a
     * matching {@code FROM_SOURCE} entry cannot derive every REQUIRED component, the result is
     * {@link DataDomainResolution.Unresolvable} — the quarantine decision is on the TYPE TAG, never
     * on the value of a look-alike DataDomain.</p>
     *
     * @param policy           the source's DataDomainPolicy (may be null → unresolvable)
     * @param functionalArea   the model's functional area
     * @param functionalDomain the model's functional domain
     * @param attrs            the ingested source-row values + source-binding metadata
     * @return a tagged {@link DataDomainResolution}; never null
     */
    default DataDomainResolution resolveIngestRow(DataDomainPolicy policy,
                                                  String functionalArea,
                                                  String functionalDomain,
                                                  SourceAttributes attrs) {
        return DataDomainResolution.unresolvable("resolveIngestRow not supported by this resolver");
    }
}
