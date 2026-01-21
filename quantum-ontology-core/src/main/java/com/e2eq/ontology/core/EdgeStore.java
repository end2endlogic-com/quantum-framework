package com.e2eq.ontology.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstraction over the persistence of ontology edges, with provenance support.
 * All operations are scoped by DataDomainInfo (orgRefName, accountNum, tenantId, dataSegment)
 * to ensure proper isolation and prevent cross-org/account leakage.
 * 
 * Implementations can be backed by Mongo (Morphia), in-memory maps, or others.
 */
public interface EdgeStore {

    /**
     * Upsert an explicit or inferred edge with full DataDomainInfo scoping.
     *
     * @param dataDomainInfo data domain info (orgRefName, accountNum, tenantId, dataSegment)
     * @param srcType    type of the source entity
     * @param src        source entity ID
     * @param p          predicate/property name
     * @param dstType    type of the destination entity
     * @param dst        destination entity ID
     * @param inferred   true if this edge was inferred by reasoner
     * @param prov       provenance metadata
     */
    void upsert(DataDomainInfo dataDomainInfo,
                String srcType,
                String src,
                String p,
                String dstType,
                String dst,
                boolean inferred,
                Map<String, Object> prov);

    /**
     * Upsert a derived edge with support provenance and DataDomainInfo scoping.
     *
     * @param dataDomainInfo data domain info
     * @param srcType    type of the source entity
     * @param src        source entity ID
     * @param p          predicate/property name
     * @param dstType    type of the destination entity
     * @param dst        destination entity ID
     * @param support    list of support records (rule IDs and path edge IDs)
     * @param prov       provenance metadata
     */
    void upsertDerived(DataDomainInfo dataDomainInfo,
                       String srcType,
                       String src,
                       String p,
                       String dstType,
                       String dst,
                       List<EdgeRecord.Support> support,
                       Map<String, Object> prov);

    /**
     * Bulk upsert multiple edge records. Each record must have its DataDomainInfo set.
     */
    void upsertMany(Collection<EdgeRecord> edges);

    // Queries - all scoped by DataDomainInfo
    
    /**
     * Find all edges originating from the given source within the DataDomainInfo.
     */
    List<EdgeRecord> findBySrc(DataDomainInfo dataDomainInfo, String src);
    
    /**
     * List outgoing edges from source with given predicate within the DataDomainInfo.
     */
    List<EdgeRecord> listOutgoingBy(DataDomainInfo dataDomainInfo, String src, String p);
    
    /**
     * List incoming edges to destination with given predicate within the DataDomainInfo.
     */
    List<EdgeRecord> listIncomingBy(DataDomainInfo dataDomainInfo, String p, String dst);

    // Deletions / pruning - all scoped by DataDomainInfo
    
    /**
     * Delete inferred edges from source with predicate where destination is not in the keep set.
     */
    void deleteInferredBySrcNotIn(DataDomainInfo dataDomainInfo, String src, String p, Collection<String> dstKeep);

    /**
     * Delete explicit edges from source with predicate where destination is not in the keep set.
     * Explicit edges are those where inferred=false AND derived=false.
     */
    void deleteExplicitBySrcNotIn(DataDomainInfo dataDomainInfo, String src, String p, Collection<String> dstKeep);

    /**
     * Delete derived/computed edges from source with predicate where destination is not in the keep set.
     * Derived edges are those where derived=true (from ComputedEdgeProvider).
     */
    void deleteDerivedBySrcNotIn(DataDomainInfo dataDomainInfo, String src, String p, Collection<String> dstKeep);

    /**
     * Remove derived edges whose support is empty or missing within the DataDomainInfo.
     */
    void pruneDerivedWithoutSupport(DataDomainInfo dataDomainInfo);
}
