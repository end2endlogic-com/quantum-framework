
package com.e2eq.ontology.policy;

import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.e2eq.ontology.mongo.EdgeRelationStore;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;

@ApplicationScoped
public class ListQueryRewriter {

    // Optional CDI injection to avoid UnsatisfiedResolutionException when no bean is present (e.g., in tests)
    @Inject
    jakarta.enterprise.inject.Instance<EdgeRelationStore> edgeDaoInstance;

    // Testing/legacy convenience: allow manual construction with a provided store
    private EdgeRelationStore edgeDao;

    public ListQueryRewriter() { }
    public ListQueryRewriter(EdgeRelationStore edgeDao){
        this.edgeDao = edgeDao;
    }

    private EdgeRelationStore store() {
        if (this.edgeDao != null) return this.edgeDao;
        if (edgeDaoInstance != null && !edgeDaoInstance.isUnsatisfied()) {
            return edgeDaoInstance.get();
        }
        throw new IllegalStateException("EdgeRelationStore bean not available; provide via CDI or constructor");
    }

    // Build a Morphia filter that caller can compose with others
    public Filter hasEdge(String tenantId, String predicate, String dstId){
        Set<String> srcIds = store().srcIdsByDst(tenantId, predicate, dstId);
        if (srcIds.isEmpty()) {
            // force empty result: impossible equality on _id
            return Filters.eq("_id", "__none__");
        }
        return Filters.in("_id", srcIds);
    }

    public Filter hasEdgeAny(String tenantId, String predicate, Collection<String> dstIds){
        if (dstIds == null || dstIds.isEmpty()) {
            return Filters.eq("_id", "__none__");
        }
        Set<String> srcIds = store().srcIdsByDstIn(tenantId, predicate, dstIds);
        if (srcIds.isEmpty()) {
            return Filters.eq("_id", "__none__");
        }
        return Filters.in("_id", srcIds);
    }

    // Require an edge to ALL the provided dstIds for the same predicate (set intersection)
    public Filter hasEdgeAll(String tenantId, String predicate, Collection<String> dstIds){
        if (dstIds == null || dstIds.isEmpty()) {
            // no constraint
            return Filters.exists("_id");
        }
        Iterator<String> it = dstIds.iterator();
        Set<String> intersection = null;
        while (it.hasNext()) {
            String dst = it.next();
            Set<String> srcIds = store().srcIdsByDst(tenantId, predicate, dst);
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

    // For multiple predicates: AND together each predicate's ALL constraint
    public Filter hasEdgeAll(String tenantId, Map<String, Collection<String>> predicateToDstIds){
        List<Filter> ands = new ArrayList<>();
        for (Map.Entry<String, Collection<String>> e : predicateToDstIds.entrySet()) {
            ands.add(hasEdgeAll(tenantId, e.getKey(), e.getValue()));
        }
        if (ands.isEmpty()) {
            return Filters.exists("_id");
        }
        return Filters.and(ands.toArray(new Filter[0]));
    }

    public Filter notHasEdge(String tenantId, String predicate, String dstId){
        Set<String> srcIds = store().srcIdsByDst(tenantId, predicate, dstId);
        if (srcIds.isEmpty()) {
            // nothing to exclude
            return Filters.exists("_id");
        }
        return Filters.nin("_id", srcIds);
    }

    public Set<String> idsForHasEdge(String tenantId, String predicate, String dstId){
        return store().srcIdsByDst(tenantId, predicate, dstId);
    }

    public Set<String> idsForHasEdgeAny(String tenantId, String predicate, Collection<String> dstIds){
        return store().srcIdsByDstIn(tenantId, predicate, dstIds);
    }
}
