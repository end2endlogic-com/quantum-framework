
package com.e2eq.ontology.policy;

import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;

@ApplicationScoped
public class ListQueryRewriter {

    // Optional CDI injection to avoid UnsatisfiedResolutionException when no bean is present (e.g., in tests)
    @Inject
    Instance<OntologyEdgeRepo> edgeRepoInstance;

    // Testing/legacy convenience: allow manual construction with a provided repo or legacy store
    private OntologyEdgeRepo edgeRepo;


    public ListQueryRewriter() { }
    public ListQueryRewriter(OntologyEdgeRepo edgeRepo){
        this.edgeRepo = edgeRepo;
    }

    private Set<String> srcIdsByDst(String tenantId, String predicate, String dstId){
        if (edgeRepo != null) return edgeRepo.srcIdsByDst(tenantId, predicate, dstId);

        if (edgeRepoInstance != null && !edgeRepoInstance.isUnsatisfied()) {
            return edgeRepoInstance.get().srcIdsByDst(tenantId, predicate, dstId);
        }
        throw new IllegalStateException("OntologyEdgeRepo bean not available; provide via CDI or constructor");
    }
    private Set<String> srcIdsByDstIn(String tenantId, String predicate, Collection<String> dstIds){
        if (edgeRepo != null) return edgeRepo.srcIdsByDstIn(tenantId, predicate, dstIds);
        if (edgeRepoInstance != null && !edgeRepoInstance.isUnsatisfied()) {
            return edgeRepoInstance.get().srcIdsByDstIn(tenantId, predicate, dstIds);
        }
        throw new IllegalStateException("OntologyEdgeRepo bean not available; provide via CDI or constructor");
    }

    // Build a Morphia filter that caller can compose with others
    public Filter hasEdge(String tenantId, String predicate, String dstId){
        Set<String> srcIds = srcIdsByDst(tenantId, predicate, dstId);
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
        Set<String> srcIds = srcIdsByDstIn(tenantId, predicate, dstIds);
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
            Set<String> srcIds = srcIdsByDst(tenantId, predicate, dst);
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
        Set<String> srcIds = srcIdsByDst(tenantId, predicate, dstId);
        if (srcIds.isEmpty()) {
            // nothing to exclude
            return Filters.exists("_id");
        }
        return Filters.nin("_id", srcIds);
    }

    public Set<String> idsForHasEdge(String tenantId, String predicate, String dstId){
        return srcIdsByDst(tenantId, predicate, dstId);
    }

    public Set<String> idsForHasEdgeAny(String tenantId, String predicate, Collection<String> dstIds){
        return srcIdsByDstIn(tenantId, predicate, dstIds);
    }
}
