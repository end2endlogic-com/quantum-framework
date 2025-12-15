
package com.e2eq.ontology.policy;

import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.core.OntologyAliasResolver;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;

/**
 * Rewrites list queries to incorporate ontology edge constraints.
 * All operations are scoped by DataDomain (orgRefName, accountNum, tenantId, dataSegment)
 * to ensure proper isolation across organizations and accounts.
 */
@ApplicationScoped
public class ListQueryRewriter {

    // Optional CDI injection to avoid UnsatisfiedResolutionException when no bean is present (e.g., in tests)
    @Inject
    Instance<OntologyEdgeRepo> edgeRepoInstance;

    // Alias resolver to normalize predicate names (supports synonyms). Optional to keep tests lightweight.
    @Inject
    Instance<OntologyAliasResolver> aliasResolverInstance;

    // Testing/legacy convenience: allow manual construction with a provided repo
    private OntologyEdgeRepo edgeRepo;

    public ListQueryRewriter() { }
    
    public ListQueryRewriter(OntologyEdgeRepo edgeRepo) {
        this.edgeRepo = edgeRepo;
    }

    private String canon(String predicate) {
        try {
            if (aliasResolverInstance != null && !aliasResolverInstance.isUnsatisfied()) {
                return aliasResolverInstance.get().canonical(predicate);
            }
        } catch (Throwable ignored) { }
        return predicate;
    }

    private OntologyEdgeRepo getRepo() {
        if (edgeRepo != null) return edgeRepo;
        if (edgeRepoInstance != null && !edgeRepoInstance.isUnsatisfied()) {
            return edgeRepoInstance.get();
        }
        throw new IllegalStateException("OntologyEdgeRepo bean not available; provide via CDI or constructor");
    }

    private Set<String> srcIdsByDst(DataDomain dataDomain, String predicate, String dstId) {
        String p = canon(predicate);
        return getRepo().srcIdsByDst(dataDomain, p, dstId);
    }

    private Set<String> srcIdsByDstIn(DataDomain dataDomain, String predicate, Collection<String> dstIds) {
        String p = canon(predicate);
        return getRepo().srcIdsByDstIn(dataDomain, p, dstIds);
    }

    /**
     * Build a Morphia filter for entities that have an edge with the given predicate pointing to dstId.
     * 
     * @param dataDomain the DataDomain context (orgRefName, accountNum, tenantId, dataSegment)
     * @param predicate  the edge predicate/property
     * @param dstId      the destination entity ID
     * @return a Filter that matches entities with the edge
     */
    public Filter hasEdge(DataDomain dataDomain, String predicate, String dstId) {
        Set<String> srcIds = srcIdsByDst(dataDomain, predicate, dstId);
        if (srcIds.isEmpty()) {
            // force empty result: impossible equality on _id
            return Filters.eq("_id", "__none__");
        }
        return Filters.in("_id", srcIds);
    }

    /**
     * Build a Morphia filter for entities that have an edge with the given predicate pointing to any of the dstIds.
     * 
     * @param dataDomain the DataDomain context
     * @param predicate  the edge predicate/property
     * @param dstIds     the set of destination entity IDs (OR semantics)
     * @return a Filter that matches entities with any of the edges
     */
    public Filter hasEdgeAny(DataDomain dataDomain, String predicate, Collection<String> dstIds) {
        if (dstIds == null || dstIds.isEmpty()) {
            return Filters.eq("_id", "__none__");
        }
        Set<String> srcIds = srcIdsByDstIn(dataDomain, predicate, dstIds);
        if (srcIds.isEmpty()) {
            return Filters.eq("_id", "__none__");
        }
        return Filters.in("_id", srcIds);
    }

    /**
     * Build a Morphia filter for entities that have edges to ALL the provided dstIds for the same predicate.
     * 
     * @param dataDomain the DataDomain context
     * @param predicate  the edge predicate/property
     * @param dstIds     the set of destination entity IDs (AND semantics via set intersection)
     * @return a Filter that matches entities with all the edges
     */
    public Filter hasEdgeAll(DataDomain dataDomain, String predicate, Collection<String> dstIds) {
        if (dstIds == null || dstIds.isEmpty()) {
            // no constraint
            return Filters.exists("_id");
        }
        Iterator<String> it = dstIds.iterator();
        Set<String> intersection = null;
        while (it.hasNext()) {
            String dst = it.next();
            Set<String> srcIds = srcIdsByDst(dataDomain, predicate, dst);
            if (intersection == null) {
                intersection = new HashSet<>(srcIds);
            } else {
                intersection.retainAll(srcIds);
            }
            if (intersection.isEmpty()) {
                return Filters.eq("_id", "__none__");
            }
        }
        return Filters.in("_id", intersection);
    }

    /**
     * Build a Morphia filter for entities satisfying ALL predicates with ALL their respective destinations.
     * 
     * @param dataDomain       the DataDomain context
     * @param predicateToDstIds map from predicate to collection of destination IDs
     * @return a Filter combining all constraints with AND
     */
    public Filter hasEdgeAll(DataDomain dataDomain, Map<String, Collection<String>> predicateToDstIds) {
        List<Filter> ands = new ArrayList<>();
        for (Map.Entry<String, Collection<String>> e : predicateToDstIds.entrySet()) {
            ands.add(hasEdgeAll(dataDomain, e.getKey(), e.getValue()));
        }
        if (ands.isEmpty()) {
            return Filters.exists("_id");
        }
        return Filters.and(ands.toArray(new Filter[0]));
    }

    /**
     * Build a Morphia filter for entities that do NOT have an edge with the given predicate pointing to dstId.
     * 
     * @param dataDomain the DataDomain context
     * @param predicate  the edge predicate/property
     * @param dstId      the destination entity ID to exclude
     * @return a Filter that excludes entities with the edge
     */
    public Filter notHasEdge(DataDomain dataDomain, String predicate, String dstId) {
        Set<String> srcIds = srcIdsByDst(dataDomain, predicate, dstId);
        if (srcIds.isEmpty()) {
            // nothing to exclude
            return Filters.exists("_id");
        }
        return Filters.nin("_id", srcIds);
    }

    /**
     * Get the set of source IDs that have an edge with the given predicate pointing to dstId.
     * 
     * @param dataDomain the DataDomain context
     * @param predicate  the edge predicate/property
     * @param dstId      the destination entity ID
     * @return set of source entity IDs
     */
    public Set<String> idsForHasEdge(DataDomain dataDomain, String predicate, String dstId) {
        return srcIdsByDst(dataDomain, predicate, dstId);
    }

    /**
     * Get the set of source IDs that have an edge with the given predicate pointing to any of the dstIds.
     * 
     * @param dataDomain the DataDomain context
     * @param predicate  the edge predicate/property
     * @param dstIds     the set of destination entity IDs
     * @return set of source entity IDs
     */
    public Set<String> idsForHasEdgeAny(DataDomain dataDomain, String predicate, Collection<String> dstIds) {
        return srcIdsByDstIn(dataDomain, predicate, dstIds);
    }
}
