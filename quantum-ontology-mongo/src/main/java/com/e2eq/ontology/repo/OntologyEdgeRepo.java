package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.core.EdgeRecord;
import com.e2eq.ontology.model.OntologyEdge;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;

import java.util.*;

/**
 * Repository for ontology edges with full DataDomain scoping.
 * All queries and mutations are filtered by the complete DataDomain
 * (orgRefName, accountNum, tenantId, dataSegment) to ensure isolation.
 */
@ApplicationScoped
public class OntologyEdgeRepo extends MorphiaRepo<OntologyEdge> {

   public void deleteAll() {
      ds().getCollection(OntologyEdge.class).deleteMany(new org.bson.Document());
   }

    /**
     * Get the datastore for the current security context realm.
     * Uses getSecurityContextRealmId() to derive realm from security context,
     * falling back to defaultRealm if no security context is available.
     */
    private Datastore ds() {
        return morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
    }

    /**
     * Get the datastore for a specific realm (used for explicit realm operations).
     */
    private Datastore ds(String realm) {
        return morphiaDataStoreWrapper.getDataStore(realm);
    }

    /**
     * Validates that the DataDomain has all required fields for scoping.
     * @throws IllegalArgumentException if any required field is missing
     */
    private void validateDataDomain(DataDomain dd) {
        if (dd == null) {
            throw new IllegalArgumentException("DataDomain must be provided");
        }
        if (dd.getOrgRefName() == null || dd.getOrgRefName().isBlank()) {
            throw new IllegalArgumentException("DataDomain.orgRefName must be provided");
        }
        if (dd.getAccountNum() == null || dd.getAccountNum().isBlank()) {
            throw new IllegalArgumentException("DataDomain.accountNum must be provided");
        }
        if (dd.getTenantId() == null || dd.getTenantId().isBlank()) {
            throw new IllegalArgumentException("DataDomain.tenantId must be provided");
        }
    }

    /**
     * Creates Morphia filters for the full DataDomain scope.
     */
    private Filter[] dataDomainFilters(DataDomain dd) {
        return new Filter[] {
            Filters.eq("dataDomain.orgRefName", dd.getOrgRefName()),
            Filters.eq("dataDomain.accountNum", dd.getAccountNum()),
            Filters.eq("dataDomain.tenantId", dd.getTenantId()),
            Filters.eq("dataDomain.dataSegment", dd.getDataSegment())
        };
    }

    /**
     * Creates a BSON Document filter for the full DataDomain scope.
     */
    private Document dataDomainDocFilter(DataDomain dd) {
        return new Document("dataDomain.orgRefName", dd.getOrgRefName())
                .append("dataDomain.accountNum", dd.getAccountNum())
                .append("dataDomain.tenantId", dd.getTenantId())
                .append("dataDomain.dataSegment", dd.getDataSegment());
    }

    /**
     * Creates a BSON Document representation of the DataDomain for upserts.
     */
    private Document dataDomainDoc(DataDomain dd) {
        return new Document("orgRefName", dd.getOrgRefName())
                .append("accountNum", dd.getAccountNum())
                .append("tenantId", dd.getTenantId())
                .append("dataSegment", dd.getDataSegment())
                .append("ownerId", dd.getOwnerId() != null ? dd.getOwnerId() : "system");
    }

    /**
     * Upsert an edge with full DataDomain scoping.
     *
     * @param dataDomain full DataDomain context (orgRefName, accountNum, tenantId, dataSegment)
     * @param srcType    type of source entity
     * @param src        source entity ID
     * @param p          predicate/property
     * @param dstType    type of destination entity
     * @param dst        destination entity ID
     * @param inferred   whether this edge is inferred
     * @param prov       provenance metadata
     */
    public void upsert(DataDomain dataDomain,
                       String srcType,
                       String src,
                       String p,
                       String dstType,
                       String dst,
                       boolean inferred,
                       Map<String, Object> prov) {
        validateDataDomain(dataDomain);
        if (srcType == null || srcType.isBlank()) {
            throw new IllegalArgumentException("srcType must be provided for edge " + src + "-" + p + "-" + dst);
        }
        if (dstType == null || dstType.isBlank()) {
            throw new IllegalArgumentException("dstType must be provided for edge " + src + "-" + p + "-" + dst);
        }
        if (src == null || src.isBlank()) {
           Log.warn("src id is null or blank can not create edge");
           return;
        }
        if (dst == null || dst.isBlank()) {
           Log.warn("dst id is null or blank can not create edge");
           return;
        }
        Datastore d = ds();
        Query<OntologyEdge> q = d.find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        q.filter(Filters.eq("src", src))
         .filter(Filters.eq("p", p))
         .filter(Filters.eq("dst", dst));
        OntologyEdge edge = q.first();
        if (edge == null) {
            edge = new OntologyEdge();
            edge.setRefName(src + "|" + p + "|" + dst);
            // Clone the dataDomain to avoid mutation issues
            DataDomain dd = new DataDomain();
            dd.setOrgRefName(dataDomain.getOrgRefName());
            dd.setAccountNum(dataDomain.getAccountNum());
            dd.setTenantId(dataDomain.getTenantId());
            dd.setDataSegment(dataDomain.getDataSegment());
            dd.setOwnerId(dataDomain.getOwnerId() != null ? dataDomain.getOwnerId() : "system");
            edge.setDataDomain(dd);
            edge.setSrc(src);
            edge.setSrcType(srcType);
            edge.setP(p);
            edge.setDst(dst);
            edge.setDstType(dstType);
        }
        edge.setSrcType(srcType);
        edge.setDstType(dstType);
        edge.setInferred(inferred);
        edge.setProv(prov);
        edge.setTs(new Date());
        save(d, edge);
    }

    /**
     * Bulk upsert edge records. Each EdgeRecord must have its DataDomainInfo set.
     * This method converts DataDomainInfo to DataDomain internally.
     */
    public void bulkUpsertEdgeRecords(Collection<EdgeRecord> edges) {
        if (edges == null || edges.isEmpty()) return;
        List<WriteModel<OntologyEdge>> ops = new ArrayList<>(edges.size());
        Date now = new Date();
        for (EdgeRecord e : edges) {
            com.e2eq.ontology.core.DataDomainInfo ddi = e.getDataDomainInfo();
            if (ddi == null) {
                Log.warnf("Skipping edge with null DataDomainInfo: %s", e);
                continue;
            }
            DataDomain dd = new DataDomain();
            dd.setOrgRefName(ddi.orgRefName());
            dd.setAccountNum(ddi.accountNum());
            dd.setTenantId(ddi.tenantId());
            dd.setDataSegment(ddi.dataSegment());
            dd.setOwnerId("system"); // default ownerId for edges
            if (dd.getOrgRefName() == null || dd.getAccountNum() == null || 
                dd.getTenantId() == null || e.getSrc() == null || e.getP() == null || e.getDst() == null) {
                Log.warnf("Skipping edge with incomplete DataDomain or missing src/p/dst: %s", e);
                continue;
            }
            // Full DataDomain-scoped filter for uniqueness
            Document filter = dataDomainDocFilter(dd)
                    .append("src", e.getSrc())
                    .append("p", e.getP())
                    .append("dst", e.getDst());
            Document setOnInsert = new Document("refName", e.getSrc() + "|" + e.getP() + "|" + e.getDst())
                    .append("dataDomain", dataDomainDoc(dd))
                    .append("src", e.getSrc())
                    .append("srcType", e.getSrcType())
                    .append("p", e.getP())
                    .append("dst", e.getDst())
                    .append("dstType", e.getDstType());
            Document update = new Document("$setOnInsert", setOnInsert)
                    .append("$set", new Document("inferred", e.isInferred())
                            .append("derived", e.isDerived())
                            .append("prov", e.getProv() == null ? new Document() : new Document(e.getProv()))
                            .append("ts", e.getTs() == null ? now : e.getTs()));
            if (e.getSupport() != null) {
                List<Document> sup = new ArrayList<>();
                for (EdgeRecord.Support s : e.getSupport()) {
                    sup.add(new Document("ruleId", s.getRuleId()).append("pathEdgeIds", s.getPathEdgeIds()));
                }
                ((Document)update.get("$set")).append("support", sup);
                ((Document)update.get("$set")).append("derived", true);
                ((Document)update.get("$set")).append("inferred", true);
            }
            ops.add(new UpdateOneModel<>(filter, update, new UpdateOptions().upsert(true)));
        }
        if (!ops.isEmpty()) {
            ds().getCollection(OntologyEdge.class).bulkWrite(ops, new BulkWriteOptions().ordered(false));
        }
    }

    /**
     * Upsert multiple edges. Each edge must have its DataDomain set.
     */
    public void upsertMany(Collection<?> edgesOrDocs) {
        if (edgesOrDocs == null || edgesOrDocs.isEmpty()) return;
        for (Object o : edgesOrDocs) {
            if (o instanceof OntologyEdge e) {
                DataDomain dd = e.getDataDomain();
                if (dd == null) {
                    throw new IllegalArgumentException("DataDomain must be set on OntologyEdge");
                }
                if (Boolean.TRUE.equals(e.isDerived())) {
                    List<EdgeRecord.Support> sup = null;
                    if (e.getSupport() != null) {
                        sup = new ArrayList<>(e.getSupport().size());
                        for (OntologyEdge.Support s : e.getSupport()) {
                            sup.add(new EdgeRecord.Support(s.getRuleId(), s.getPathEdgeIds()));
                        }
                    }
                    upsertDerived(dd, e.getSrcType(), e.getSrc(), e.getP(), e.getDstType(), e.getDst(), sup, e.getProv());
                } else {
                    upsert(dd, e.getSrcType(), e.getSrc(), e.getP(), e.getDstType(), e.getDst(), e.isInferred(), e.getProv());
                }
            } else if (o instanceof org.bson.Document d) {
                // Extract DataDomain from document - could be nested or flat
                @SuppressWarnings("unchecked")
                Map<String, Object> ddDoc = (Map<String, Object>) d.get("dataDomain");
                DataDomain dd = new DataDomain();
                if (ddDoc != null) {
                    dd.setOrgRefName((String) ddDoc.get("orgRefName"));
                    dd.setAccountNum((String) ddDoc.get("accountNum"));
                    dd.setTenantId((String) ddDoc.get("tenantId"));
                    dd.setDataSegment(ddDoc.get("dataSegment") != null ? ((Number) ddDoc.get("dataSegment")).intValue() : 0);
                    dd.setOwnerId((String) ddDoc.getOrDefault("ownerId", "system"));
                } else {
                    throw new IllegalArgumentException("dataDomain must be provided in edge document");
                }
                String srcType = d.getString("srcType");
                String src = d.getString("src");
                String p = d.getString("p");
                String dstType = d.getString("dstType");
                String dst = d.getString("dst");
                boolean inferred = Boolean.TRUE.equals(d.getBoolean("inferred"));
                @SuppressWarnings("unchecked")
                Map<String, Object> prov = (Map<String, Object>) d.getOrDefault("prov", Map.of());
                if (srcType == null || dstType == null) {
                    throw new IllegalArgumentException("srcType and dstType must be provided in edge document");
                }
                if (Boolean.TRUE.equals(d.getBoolean("derived"))) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> supDocs = (List<Map<String, Object>>) d.get("support");
                    List<EdgeRecord.Support> support = null;
                    if (supDocs != null) {
                        support = new ArrayList<>();
                        for (Map<String, Object> sd : supDocs) {
                            String ruleId = (String) sd.get("ruleId");
                            @SuppressWarnings("unchecked")
                            List<String> pathEdgeIds = (List<String>) sd.get("pathEdgeIds");
                            support.add(new EdgeRecord.Support(ruleId, pathEdgeIds));
                        }
                    }
                    upsertDerived(dd, srcType, src, p, dstType, dst, support, prov);
                } else {
                    upsert(dd, srcType, src, p, dstType, dst, inferred, prov);
                }
            }
        }
    }

    /**
     * Delete edges by source within the given DataDomain.
     */
    public void deleteBySrc(DataDomain dataDomain, String src, boolean inferredOnly) {
        validateDataDomain(dataDomain);
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        q.filter(Filters.eq("src", src));
        if (inferredOnly) q.filter(Filters.eq("inferred", true));
        q.delete();
    }

    /**
     * Delete edges by source and predicate within the given DataDomain.
     */
    public void deleteBySrcAndPredicate(DataDomain dataDomain, String src, String p) {
        validateDataDomain(dataDomain);
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        q.filter(Filters.eq("src", src))
         .filter(Filters.eq("p", p))
         .delete();
    }

    /**
     * Delete inferred edges from source with predicate where destination is not in keep set.
     */
    public void deleteInferredBySrcNotIn(DataDomain dataDomain, String src, String p, Collection<String> dstKeep) {
        validateDataDomain(dataDomain);
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        q.filter(Filters.eq("src", src))
         .filter(Filters.eq("p", p))
         .filter(Filters.eq("inferred", true))
         .filter(Filters.nin("dst", dstKeep))
         .delete();
    }

    /**
     * Delete explicit edges from source with predicate where destination is not in keep set.
     */
    public void deleteExplicitBySrcNotIn(DataDomain dataDomain, String src, String p, Collection<String> dstKeep) {
        validateDataDomain(dataDomain);
        // Treat explicit edges as those where inferred != true (handles nulls)
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        q.filter(Filters.eq("src", src))
         .filter(Filters.eq("p", p))
         .filter(Filters.ne("inferred", true))
         .filter(Filters.nin("dst", dstKeep))
         .delete();
    }

    /**
     * Find source IDs for edges with given predicate pointing to destination, within the DataDomain.
     */
    public Set<String> srcIdsByDst(DataDomain dataDomain, String p, String dst) {
        validateDataDomain(dataDomain);
        Set<String> ids = new HashSet<>();
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        for (OntologyEdge e : q.filter(Filters.eq("p", p)).filter(Filters.eq("dst", dst))) {
            ids.add(e.getSrc());
        }
        return ids;
    }

    /**
     * Find source IDs for edges with given predicate pointing to any destination in set, within the DataDomain.
     */
    public Set<String> srcIdsByDstIn(DataDomain dataDomain, String p, Collection<String> dstIds) {
        if (dstIds == null || dstIds.isEmpty()) return Set.of();
        validateDataDomain(dataDomain);
        Set<String> ids = new HashSet<>();
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        for (OntologyEdge e : q.filter(Filters.eq("p", p)).filter(Filters.in("dst", dstIds))) {
            ids.add(e.getSrc());
        }
        return ids;
    }

    /**
     * Group source IDs by destination for edges with given predicate, within the DataDomain.
     */
    public Map<String, Set<String>> srcIdsByDstGrouped(DataDomain dataDomain, String p, Collection<String> dstIds) {
        Map<String, Set<String>> map = new HashMap<>();
        if (dstIds == null || dstIds.isEmpty()) return map;
        validateDataDomain(dataDomain);
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        for (OntologyEdge e : q.filter(Filters.eq("p", p)).filter(Filters.in("dst", dstIds)).iterator().toList()) {
            map.computeIfAbsent(e.getDst(), k -> new HashSet<>()).add(e.getSrc());
        }
        return map;
    }

    /**
     * Find all edges from the given source within the DataDomain.
     */
    public List<OntologyEdge> findBySrc(DataDomain dataDomain, String src) {
        validateDataDomain(dataDomain);
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        return q.filter(Filters.eq("src", src)).iterator().toList();
    }

    /**
     * Find all edges pointing to the given destination within the DataDomain.
     */
    public List<OntologyEdge> findByDst(DataDomain dataDomain, String dst) {
        validateDataDomain(dataDomain);
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        return q.filter(Filters.eq("dst", dst)).iterator().toList();
    }

    /**
     * Find edges with given predicate pointing to destination, within the DataDomain.
     */
    public List<OntologyEdge> findByDstAndP(DataDomain dataDomain, String dst, String p) {
        validateDataDomain(dataDomain);
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        return q.filter(Filters.eq("dst", dst)).filter(Filters.eq("p", p)).iterator().toList();
    }

    /**
     * Find edges from source with given predicate, within the DataDomain.
     */
    public List<OntologyEdge> findBySrcAndP(DataDomain dataDomain, String src, String p) {
        validateDataDomain(dataDomain);
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        return q.filter(Filters.eq("src", src)).filter(Filters.eq("p", p)).iterator().toList();
    }

    /**
     * Find all edges with the given property/predicate, within the DataDomain.
     */
    public List<OntologyEdge> findByProperty(DataDomain dataDomain, String p) {
        validateDataDomain(dataDomain);
        Query<OntologyEdge> q = ds().find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        return q.filter(Filters.eq("p", p)).iterator().toList();
    }

    /**
     * Upsert a derived edge with support provenance, scoped by DataDomain.
     */
    public void upsertDerived(DataDomain dataDomain,
                              String srcType,
                              String src,
                              String p,
                              String dstType,
                              String dst,
                              List<EdgeRecord.Support> support,
                              Map<String, Object> prov) {
        validateDataDomain(dataDomain);
        if (srcType == null || srcType.isBlank()) {
            throw new IllegalArgumentException("srcType must be provided for edge " + src + "-" + p + "-" + dst);
        }
        if (dstType == null || dstType.isBlank()) {
            throw new IllegalArgumentException("dstType must be provided for edge " + src + "-" + p + "-" + dst);
        }
        if (src == null || src.isBlank()) {
            Log.warn("src id is null or blank can not create edge");
            return;
        }
        if (dst == null || dst.isBlank()) {
            Log.warn("dst id is null or blank can not create edge");
            return;
        }
        Datastore d = ds();
        Query<OntologyEdge> q = d.find(OntologyEdge.class);
        for (Filter f : dataDomainFilters(dataDomain)) {
            q.filter(f);
        }
        q.filter(Filters.eq("src", src))
         .filter(Filters.eq("p", p))
         .filter(Filters.eq("dst", dst));
        OntologyEdge edge = q.first();
        if (edge == null) {
            edge = new OntologyEdge();
            edge.setRefName(src + "|" + p + "|" + dst);
            // Clone the dataDomain
            DataDomain dd = new DataDomain();
            dd.setOrgRefName(dataDomain.getOrgRefName());
            dd.setAccountNum(dataDomain.getAccountNum());
            dd.setTenantId(dataDomain.getTenantId());
            dd.setDataSegment(dataDomain.getDataSegment());
            dd.setOwnerId(dataDomain.getOwnerId() != null ? dataDomain.getOwnerId() : "system");
            edge.setDataDomain(dd);
            edge.setSrc(src);
            edge.setSrcType(srcType);
            edge.setP(p);
            edge.setDst(dst);
            edge.setDstType(dstType);
        }
        edge.setSrcType(srcType);
        edge.setDstType(dstType);
        edge.setInferred(true);
        edge.setDerived(true);
        if (support != null) {
            List<OntologyEdge.Support> sup = new ArrayList<>(support.size());
            for (EdgeRecord.Support s : support) {
                OntologyEdge.Support x = new OntologyEdge.Support();
                x.setRuleId(s.getRuleId());
                x.setPathEdgeIds(s.getPathEdgeIds());
                sup.add(x);
            }
            edge.setSupport(sup);
        } else {
            edge.setSupport(null);
        }
        edge.setProv(prov);
        edge.setTs(new Date());
        save(d, edge);
    }

    /**
     * Remove derived edges whose support is empty or missing, within the DataDomain.
     */
    public void pruneDerivedWithoutSupport(DataDomain dataDomain) {
        validateDataDomain(dataDomain);
        // Delete derived edges whose support is null or empty
        org.bson.Document filter = dataDomainDocFilter(dataDomain)
                .append("derived", true)
                .append("$or", List.of(
                        new org.bson.Document("support", new org.bson.Document("$exists", false)),
                        new org.bson.Document("support", new org.bson.Document("$size", 0))
                ));
        ds().getCollection(OntologyEdge.class).deleteMany(filter);
    }

    /**
     * Delete all derived edges within the DataDomain. Used by force reindex.
     */
    public void deleteDerivedByDataDomain(DataDomain dataDomain) {
        validateDataDomain(dataDomain);
        org.bson.Document filter = dataDomainDocFilter(dataDomain)
                .append("derived", true);
        ds().getCollection(OntologyEdge.class).deleteMany(filter);
    }
}
