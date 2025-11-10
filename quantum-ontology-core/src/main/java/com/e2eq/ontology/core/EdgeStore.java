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

    void upsert(String tenantId,
                String srcType,
                String src,
                String p,
                String dstType,
                String dst,
                boolean inferred,
                Map<String, Object> prov);

    void upsertMany(Collection<EdgeRecord> edges);

    void deleteInferredBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep);

    void deleteExplicitBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep);

    List<EdgeRecord> findBySrc(String tenantId, String src);
}
