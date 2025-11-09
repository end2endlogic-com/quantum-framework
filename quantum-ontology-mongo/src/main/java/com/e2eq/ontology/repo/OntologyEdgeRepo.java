package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.model.OntologyEdge;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;


import java.util.*;

@ApplicationScoped
public class OntologyEdgeRepo extends MorphiaRepo<OntologyEdge> {

   public void deleteAll() {
      ds().getCollection(OntologyEdge.class).deleteMany(new org.bson.Document());
   }

    private Datastore ds() {
        // Use the default realm configured for the service; MorphiaRepo injects it.
        return morphiaDataStore.getDataStore(defaultRealm);
    }

    public void upsert(String tenantId,
                       String srcType,
                       String src,
                       String p,
                       String dstType,
                       String dst,
                       boolean inferred,
                       Map<String, Object> prov) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must be provided");
        }
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
        Query<OntologyEdge> q = d.find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("src", src))
                .filter(Filters.eq("p", p))
                .filter(Filters.eq("dst", dst));
        OntologyEdge edge = q.first();
        if (edge == null) {
            edge = new OntologyEdge();
            edge.setRefName(src + "|" + p + "|" + dst);
            DataDomain dd = new DataDomain();
            // Populate required DataDomain fields for ontology edges; tests use default realm without user context
            dd.setTenantId(tenantId);
            // Derive minimal defaults; in production these should be set from SecurityContext/Seed context
            if (dd.getOrgRefName() == null) dd.setOrgRefName("ontology");
            if (dd.getAccountNum() == null) dd.setAccountNum("0000000000");
            if (dd.getOwnerId() == null) dd.setOwnerId("system");
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

    public void upsertMany(Collection<?> edgesOrDocs) {
        if (edgesOrDocs == null || edgesOrDocs.isEmpty()) return;
        for (Object o : edgesOrDocs) {
            if (o instanceof OntologyEdge e) {
                String tenantId = e.getDataDomain() != null ? e.getDataDomain().getTenantId() : null;
                upsert(tenantId, e.getSrcType(), e.getSrc(), e.getP(), e.getDstType(), e.getDst(), e.isInferred(), e.getProv());
            } else if (o instanceof org.bson.Document d) {
                String tenantId = d.getString("tenantId");
                String srcType = d.getString("srcType");
                String src = d.getString("src");
                String p = d.getString("p");
                String dstType = d.getString("dstType");
                String dst = d.getString("dst");
                boolean inferred = Boolean.TRUE.equals(d.getBoolean("inferred"));
                @SuppressWarnings("unchecked")
                Map<String, Object> prov = (Map<String, Object>) d.getOrDefault("prov", Map.of());
                if (tenantId == null || srcType == null || dstType == null) {
                    throw new IllegalArgumentException("tenantId, srcType, and dstType must be provided in edge document");
                }
                upsert(tenantId, srcType, src, p, dstType, dst, inferred, prov);
            }
        }
    }

    public void deleteBySrc(String tenantId, String src, boolean inferredOnly) {
        Query<OntologyEdge> q = ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("src", src));
        if (inferredOnly) q.filter(Filters.eq("inferred", true));
        q.delete();
    }

    public void deleteBySrcAndPredicate(String tenantId, String src, String p) {
        ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("src", src))
                .filter(Filters.eq("p", p))
                .delete();
    }

    public void deleteInferredBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep) {
        ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("src", src))
                .filter(Filters.eq("p", p))
                .filter(Filters.eq("inferred", true))
                .filter(Filters.nin("dst", dstKeep))
                .delete();
    }

    public void deleteExplicitBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep) {
        // Treat explicit edges as those where inferred != true (handles nulls)
        ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("src", src))
                .filter(Filters.eq("p", p))
                .filter(Filters.ne("inferred", true))
                .filter(Filters.nin("dst", dstKeep))
                .delete();
    }

    public Set<String> srcIdsByDst(String tenantId, String p, String dst) {
        Set<String> ids = new HashSet<>();
        for (OntologyEdge e : ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("p", p))
                .filter(Filters.eq("dst", dst))) {
            ids.add(e.getSrc());
        }
        return ids;
    }

    public Set<String> srcIdsByDstIn(String tenantId, String p, Collection<String> dstIds) {
        if (dstIds == null || dstIds.isEmpty()) return Set.of();
        Set<String> ids = new HashSet<>();
        for (OntologyEdge e : ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("p", p))
                .filter(Filters.in("dst", dstIds))) {
            ids.add(e.getSrc());
        }
        return ids;
    }

    public Map<String, Set<String>> srcIdsByDstGrouped(String tenantId, String p, Collection<String> dstIds) {
        Map<String, Set<String>> map = new HashMap<>();
        if (dstIds == null || dstIds.isEmpty()) return map;
        for (OntologyEdge e : ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("p", p))
                .filter(Filters.in("dst", dstIds))
                .iterator().toList()) {
            map.computeIfAbsent(e.getDst(), k -> new HashSet<>()).add(e.getSrc());
        }
        return map;
    }

    public List<OntologyEdge> findBySrc(String tenantId, String src) {
        return ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("src", src))
                .iterator()
                .toList();
    }

    public List<OntologyEdge> findByDst(String tenantId, String dst) {
        return ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("dst", dst))
                .iterator()
                .toList();
    }

    public List<OntologyEdge> findByDstAndP(String tenantId, String dst, String p) {
        return ds().find(OntologyEdge.class)
                .filter(Filters.eq("dataDomain.tenantId", tenantId))
                .filter(Filters.eq("dst", dst))
                .filter(Filters.eq("p", p))
                .iterator()
                .toList();
    }
}
