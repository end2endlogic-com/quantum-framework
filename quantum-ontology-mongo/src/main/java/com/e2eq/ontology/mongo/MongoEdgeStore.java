package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.EdgeRecord;
import com.e2eq.ontology.core.EdgeStore;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mongo-backed EdgeStore delegating to OntologyEdgeRepo (Morphia).
 */
@ApplicationScoped
public class MongoEdgeStore implements EdgeStore {

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Override
    public void upsert(String tenantId, String srcType, String src, String p, String dstType, String dst, boolean inferred, Map<String, Object> prov) {
        edgeRepo.upsert(tenantId, srcType, src, p, dstType, dst, inferred, prov);
    }

    @Override
    public void upsertDerived(String tenantId, String srcType, String src, String p, String dstType, String dst, List<EdgeRecord.Support> support, Map<String, Object> prov) {
        // Delegate to repo; both derived and inferred considered true for backward compat
        edgeRepo.upsertDerived(tenantId, srcType, src, p, dstType, dst, support, prov);
    }

    @Override
    public void upsertMany(Collection<EdgeRecord> edges) {
        if (edges == null || edges.isEmpty()) return;
        try {
            edgeRepo.bulkUpsertEdgeRecords(edges);
        } catch (Throwable t) {
            // Fallback to per-item upsert to preserve behavior if bulk fails for any reason
            for (EdgeRecord e : edges) {
                if (e.isDerived()) {
                    upsertDerived(e.getTenantId(), e.getSrcType(), e.getSrc(), e.getP(), e.getDstType(), e.getDst(), e.getSupport(), e.getProv());
                } else {
                    upsert(e.getTenantId(), e.getSrcType(), e.getSrc(), e.getP(), e.getDstType(), e.getDst(), e.isInferred(), e.getProv());
                }
            }
        }
    }

    @Override
    public void deleteInferredBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep) {
        edgeRepo.deleteInferredBySrcNotIn(tenantId, src, p, dstKeep);
    }

    @Override
    public void deleteExplicitBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep) {
        edgeRepo.deleteExplicitBySrcNotIn(tenantId, src, p, dstKeep);
    }

    private static EdgeRecord toRecord(OntologyEdge e) {
        EdgeRecord r = new EdgeRecord();
        r.setTenantId(e.getDataDomain() != null ? e.getDataDomain().getTenantId() : null);
        r.setSrc(e.getSrc());
        r.setSrcType(e.getSrcType());
        r.setP(e.getP());
        r.setDst(e.getDst());
        r.setDstType(e.getDstType());
        r.setInferred(e.isInferred());
        r.setDerived(e.isDerived());
        r.setProv(e.getProv());
        if (e.getSupport() != null) {
            List<EdgeRecord.Support> sup = e.getSupport().stream().map(s -> new EdgeRecord.Support(s.getRuleId(), s.getPathEdgeIds())).collect(Collectors.toList());
            r.setSupport(sup);
        }
        r.setTs(e.getTs() != null ? e.getTs() : new Date());
        return r;
    }

    @Override
    public List<EdgeRecord> findBySrc(String tenantId, String src) {
        List<OntologyEdge> list = edgeRepo.findBySrc(tenantId, src);
        List<EdgeRecord> out = new ArrayList<>(list.size());
        for (OntologyEdge e : list) {
            out.add(toRecord(e));
        }
        return out;
    }

    @Override
    public List<EdgeRecord> listOutgoingBy(String tenantId, String src, String p) {
        List<OntologyEdge> list = edgeRepo.findBySrcAndP(tenantId, src, p);
        List<EdgeRecord> out = new ArrayList<>(list.size());
        for (OntologyEdge e : list) out.add(toRecord(e));
        return out;
    }

    @Override
    public List<EdgeRecord> listIncomingBy(String tenantId, String p, String dst) {
        List<OntologyEdge> list = edgeRepo.findByDstAndP(tenantId, dst, p);
        List<EdgeRecord> out = new ArrayList<>(list.size());
        for (OntologyEdge e : list) out.add(toRecord(e));
        return out;
    }

    @Override
    public void pruneDerivedWithoutSupport(String tenantId) {
        edgeRepo.pruneDerivedWithoutSupport(tenantId);
    }
}
