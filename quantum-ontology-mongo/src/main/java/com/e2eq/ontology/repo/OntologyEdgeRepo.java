package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.model.OntologyEdge;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.conversions.Bson;

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

    public void upsert(String tenantId, String src, String p, String dst, boolean inferred, Map<String, Object> prov) {
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
            edge.setP(p);
            edge.setDst(dst);
        }
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
                upsert(tenantId, e.getSrc(), e.getP(), e.getDst(), e.isInferred(), e.getProv());
            } else if (o instanceof org.bson.Document d) {
                String tenantId = d.getString("tenantId");
                String src = d.getString("src");
                String p = d.getString("p");
                String dst = d.getString("dst");
                boolean inferred = Boolean.TRUE.equals(d.getBoolean("inferred"));
                @SuppressWarnings("unchecked")
                Map<String, Object> prov = (Map<String, Object>) d.getOrDefault("prov", Map.of());
                upsert(tenantId, src, p, dst, inferred, prov);
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
}
