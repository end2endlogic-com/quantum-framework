package com.e2eq.ontology.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstraction over the persistence of ontology edges, with provenance support.
 * Implementations can be backed by Mongo (Morphia), in-memory maps, or others.
 */
public interface EdgeStore {

    // Legacy upsert used for explicit and inferred (pre-derived) edges
    void upsert(String tenantId,
                String srcType,
                String src,
                String p,
                String dstType,
                String dst,
                boolean inferred,
                Map<String, Object> prov);

    // New: upsert an implied edge with derived flag and structured support
    void upsertDerived(String tenantId,
                       String srcType,
                       String src,
                       String p,
                       String dstType,
                       String dst,
                       List<EdgeRecord.Support> support,
                       Map<String, Object> prov);

    void upsertMany(Collection<EdgeRecord> edges);

    // Queries
    List<EdgeRecord> findBySrc(String tenantId, String src);
    List<EdgeRecord> listOutgoingBy(String tenantId, String src, String p);
    List<EdgeRecord> listIncomingBy(String tenantId, String p, String dst);

    // Deletions / pruning
    void deleteInferredBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep);

    void deleteExplicitBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep);

    // Remove derived edges whose support is empty or missing
    void pruneDerivedWithoutSupport(String tenantId);
}
