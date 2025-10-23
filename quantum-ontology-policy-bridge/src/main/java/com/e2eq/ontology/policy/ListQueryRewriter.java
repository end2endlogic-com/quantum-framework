
package com.e2eq.ontology.policy;

import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.e2eq.ontology.mongo.EdgeRelationStore;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;

@ApplicationScoped
public class ListQueryRewriter {

    @Inject
    EdgeRelationStore edgeDao;

    // Testing/legacy convenience: allow manual construction with a provided store
    public ListQueryRewriter() { }
    public ListQueryRewriter(EdgeRelationStore edgeDao){
        this.edgeDao = edgeDao;
    }

    // Build a Morphia filter that caller can compose with others
    public Filter hasEdge(String tenantId, String predicate, String dstId){
        Set<String> srcIds = edgeDao.srcIdsByDst(tenantId, predicate, dstId);
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
        Set<String> srcIds = edgeDao.srcIdsByDstIn(tenantId, predicate, dstIds);
        if (srcIds.isEmpty()) {
            return Filters.eq("_id", "__none__");
        }
        return Filters.in("_id", srcIds);
    }

    public Filter hasEdgeAll(String tenantId, Map<String, Collection<String>> predicateToDstIds){
        List<Filter> ands = new ArrayList<>();
        for (Map.Entry<String, Collection<String>> e : predicateToDstIds.entrySet()) {
            ands.add(hasEdgeAny(tenantId, e.getKey(), e.getValue()));
        }
        if (ands.isEmpty()) {
            return Filters.exists("_id");
        }
        return Filters.and(ands.toArray(new Filter[0]));
    }

    public Filter notHasEdge(String tenantId, String predicate, String dstId){
        Set<String> srcIds = edgeDao.srcIdsByDst(tenantId, predicate, dstId);
        if (srcIds.isEmpty()) {
            // nothing to exclude
            return Filters.exists("_id");
        }
        return Filters.nin("_id", srcIds);
    }

    public Set<String> idsForHasEdge(String tenantId, String predicate, String dstId){
        return edgeDao.srcIdsByDst(tenantId, predicate, dstId);
    }

    public Set<String> idsForHasEdgeAny(String tenantId, String predicate, Collection<String> dstIds){
        return edgeDao.srcIdsByDstIn(tenantId, predicate, dstIds);
    }
}
