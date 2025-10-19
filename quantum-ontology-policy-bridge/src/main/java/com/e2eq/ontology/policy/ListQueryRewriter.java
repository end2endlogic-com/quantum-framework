
package com.e2eq.ontology.policy;

import java.util.*;
import org.bson.conversions.Bson;
import com.mongodb.client.model.Filters;
import com.e2eq.ontology.mongo.EdgeDao;

public class ListQueryRewriter {

    private final EdgeDao edgeDao;

    public ListQueryRewriter(EdgeDao edgeDao){
        this.edgeDao = edgeDao;
    }

    public Bson rewriteForHasEdge(Bson base, String tenantId, String predicate, String dstId){
        Set<String> srcIds = edgeDao.srcIdsByDst(tenantId, predicate, dstId);
        if (srcIds.isEmpty()) {
            // force empty result
            return Filters.and(base, Filters.eq("_id", "__none__"));
        }
        return Filters.and(base, Filters.in("_id", srcIds));
    }

    public Bson rewriteForHasEdgeAny(Bson base, String tenantId, String predicate, Collection<String> dstIds){
        if (dstIds == null || dstIds.isEmpty()) {
            return Filters.and(base, Filters.eq("_id", "__none__"));
        }
        Set<String> srcIds = edgeDao.srcIdsByDstIn(tenantId, predicate, dstIds);
        if (srcIds.isEmpty()) {
            return Filters.and(base, Filters.eq("_id", "__none__"));
        }
        return Filters.and(base, Filters.in("_id", srcIds));
    }

    public Bson rewriteForHasEdgeAll(Bson base, String tenantId, Map<String, Collection<String>> predicateToDstIds){
        Bson result = base;
        for (Map.Entry<String, Collection<String>> e : predicateToDstIds.entrySet()) {
            result = rewriteForHasEdgeAny(result, tenantId, e.getKey(), e.getValue());
        }
        return result;
    }

    public Bson rewriteForNotHasEdge(Bson base, String tenantId, String predicate, String dstId){
        Set<String> srcIds = edgeDao.srcIdsByDst(tenantId, predicate, dstId);
        if (srcIds.isEmpty()) {
            // nothing to exclude
            return base;
        }
        return Filters.and(base, Filters.nin("_id", srcIds));
    }

    public Set<String> idsForHasEdge(String tenantId, String predicate, String dstId){
        return edgeDao.srcIdsByDst(tenantId, predicate, dstId);
    }

    public Set<String> idsForHasEdgeAny(String tenantId, String predicate, Collection<String> dstIds){
        return edgeDao.srcIdsByDstIn(tenantId, predicate, dstIds);
    }
}
