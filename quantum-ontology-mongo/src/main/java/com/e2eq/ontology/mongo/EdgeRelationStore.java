package com.e2eq.ontology.mongo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstraction for querying and mutating ontology edges used by materializer and rewriter.
 */
public interface EdgeRelationStore {
    void upsert(String tenantId, String src, String p, String dst, boolean inferred, Map<String, Object> prov);
    void upsertMany(Collection<?> edgesOrDocs);
    void deleteBySrc(String tenantId, String src, boolean inferredOnly);
    void deleteBySrcAndPredicate(String tenantId, String src, String p);
    void deleteInferredBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep);
    Set<String> srcIdsByDst(String tenantId, String p, String dst);
    Set<String> srcIdsByDstIn(String tenantId, String p, Collection<String> dstIds);
    Map<String, Set<String>> srcIdsByDstGrouped(String tenantId, String p, Collection<String> dstIds);
    List<?> findBySrc(String tenantId, String src);
}
