package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;

import java.util.List;

/**
 * Universal DataDomain coordinate and scope resolution engine.
 *
 * <p>Disentangles segmentation semantics from persistence mechanics. Provides store-free
 * coordinate resolution across creation, governed ingestion, query scoping, and multi-entity
 * ontology edge traversals with fail-closed security guarantees.</p>
 */
public interface DataDomainResolver {

    /**
     * Resolve the DataDomain to stamp on a create operation.
     *
     * @param functionalArea the model's functional area
     * @param functionalDomain the model's functional domain
     * @return a non-null DataDomain; if no policy applies, returns the principal's domain
     */
    DataDomain resolveForCreate(String functionalArea, String functionalDomain);

    /**
     * Resolve the DataDomain for a create or upsert operation with access to the
     * concrete entity instance being persisted.
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
     * @param functionalArea the model's functional area
     * @param functionalDomain the model's functional domain
     * @param entity the concrete entity being persisted (may be null for raw source rows)
     * @param attrs the ingested source-row values and source-binding metadata
     * @return a non-null DataDomain
     */
    default DataDomain resolveForCreate(String functionalArea, String functionalDomain, Object entity, SourceAttributes attrs) {
        return resolveForCreate(functionalArea, functionalDomain, entity);
    }

    /**
     * Governed ingest resolution. Resolves a single ingested source row to an explicit,
     * fail-closed {@link DataDomainResolution} given an EXPLICIT {@code policy} and the row's
     * {@link SourceAttributes}.
     *
     * @param policy the source's DataDomainPolicy (may be null → unresolvable)
     * @param functionalArea the model's functional area
     * @param functionalDomain the model's functional domain
     * @param attrs the ingested source-row values + source-binding metadata
     * @return a tagged {@link DataDomainResolution}; never null
     */
    default DataDomainResolution resolveIngestRow(DataDomainPolicy policy,
                                                  String functionalArea,
                                                  String functionalDomain,
                                                  SourceAttributes attrs) {
        return DataDomainResolution.unresolvable("resolveIngestRow not supported by this resolver");
    }

    /**
     * Resolve the effective DataDomain coordinate to scope queries against the given functional area,
     * functional domain, and optional target model class.
     *
     * <p>Enforces fail-closed semantics: if principal is null, returns {@link DataDomainResolution.Unresolvable}.</p>
     *
     * @param principal the authenticated principal context
     * @param functionalArea the target functional area (e.g. "Sales", "ontology")
     * @param functionalDomain the target functional domain (e.g. "Orders", "edges")
     * @param modelClass the target entity model class (may be null)
     * @return a tagged {@link DataDomainResolution}; fail-closed if principal is absent or coordinates cannot be resolved
     */
    default DataDomainResolution resolveForQuery(PrincipalContext principal,
                                                 String functionalArea,
                                                 String functionalDomain,
                                                 Class<?> modelClass) {
        if (principal == null) {
            return DataDomainResolution.unresolvable("No principal context provided for query resolution");
        }
        DataDomainPolicy policy = principal.getDataDomainPolicy();
        if (policy != null && policy.getPolicyEntries() != null && !policy.getPolicyEntries().isEmpty()) {
            List<String> keys = List.of(
                functionalArea + ":" + functionalDomain,
                functionalArea + ":*",
                "*:" + functionalDomain,
                "*:*"
            );
            for (String key : keys) {
                DataDomainPolicyEntry entry = policy.getPolicyEntries().get(key);
                if (entry != null) {
                    DataDomainPolicyEntry.ResolutionMode mode = entry.getResolutionMode() != null
                        ? entry.getResolutionMode()
                        : DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL;
                    if (mode == DataDomainPolicyEntry.ResolutionMode.FIXED) {
                        if (entry.getDataDomains() != null && !entry.getDataDomains().isEmpty()) {
                            DataDomain dd = entry.getDataDomains().get(0);
                            if (dd != null) return DataDomainResolution.resolved(dd);
                        }
                    } else if (mode == DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL) {
                        if (principal.getDataDomain() != null) {
                            return DataDomainResolution.resolved(principal.getDataDomain());
                        }
                    }
                }
            }
        }
        // Fallback to principal's credential domain if present
        if (principal.getDataDomain() != null) {
            return DataDomainResolution.resolved(principal.getDataDomain());
        }
        return DataDomainResolution.unresolvable("Unable to resolve query DataDomain coordinate for " + functionalArea + ":" + functionalDomain);
    }

    /**
     * Resolve the DataDomain coordinate across an ontology graph traversal hop.
     * Enforces monotonic security and fail-closed restriction along graph edges.
     *
     * @param principal the authenticated principal context
     * @param edgeType the relationship / predicate type being traversed (e.g. "suppliedBy", "placedBy")
     * @param targetFunctionalArea the functional area of the target entity
     * @param targetFunctionalDomain the functional domain of the target entity
     * @return a tagged {@link DataDomainResolution}; fail-closed if traversal across the edge is denied or unresolvable
     */
    default DataDomainResolution resolveForHop(PrincipalContext principal,
                                               String edgeType,
                                               String targetFunctionalArea,
                                               String targetFunctionalDomain) {
        if (principal == null) {
            return DataDomainResolution.unresolvable("No principal context provided for edge traversal hop");
        }
        if (edgeType == null || edgeType.isBlank()) {
            return DataDomainResolution.unresolvable("Edge type must not be blank for traversal hop");
        }
        DataDomainPolicy policy = principal.getDataDomainPolicy();
        if (policy != null && policy.getPolicyEntries() != null && !policy.getPolicyEntries().isEmpty()) {
            List<String> keys = List.of(
                "edge/" + edgeType + "/" + targetFunctionalArea + ":" + targetFunctionalDomain,
                "edge/" + edgeType + "/*",
                "hop/" + targetFunctionalArea + ":" + targetFunctionalDomain,
                targetFunctionalArea + ":" + targetFunctionalDomain,
                targetFunctionalArea + ":*",
                "*:" + targetFunctionalDomain,
                "*:*"
            );
            for (String key : keys) {
                DataDomainPolicyEntry entry = policy.getPolicyEntries().get(key);
                if (entry != null) {
                    DataDomainPolicyEntry.ResolutionMode mode = entry.getResolutionMode() != null
                        ? entry.getResolutionMode()
                        : DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL;
                    if (mode == DataDomainPolicyEntry.ResolutionMode.FIXED) {
                        if (entry.getDataDomains() != null && !entry.getDataDomains().isEmpty()) {
                            DataDomain dd = entry.getDataDomains().get(0);
                            if (dd != null) return DataDomainResolution.resolved(dd);
                        }
                    } else if (mode == DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL) {
                        if (principal.getDataDomain() != null) {
                            return DataDomainResolution.resolved(principal.getDataDomain());
                        }
                    }
                }
            }
        }
        if (principal.getDataDomain() != null) {
            return DataDomainResolution.resolved(principal.getDataDomain());
        }
        return DataDomainResolution.unresolvable("Unable to resolve hop DataDomain coordinate for edge " + edgeType + " to " + targetFunctionalArea + ":" + targetFunctionalDomain);
    }
}
