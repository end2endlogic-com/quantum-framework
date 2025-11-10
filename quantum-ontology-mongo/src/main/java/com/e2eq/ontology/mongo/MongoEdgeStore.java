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
    public void upsertMany(Collection<EdgeRecord> edges) {
        if (edges == null || edges.isEmpty()) return;
        for (EdgeRecord e : edges) {
            upsert(e.getTenantId(), e.getSrcType(), e.getSrc(), e.getP(), e.getDstType(), e.getDst(), e.isInferred(), e.getProv());
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

    @Override
    public List<EdgeRecord> findBySrc(String tenantId, String src) {
        List<OntologyEdge> list = edgeRepo.findBySrc(tenantId, src);
        List<EdgeRecord> out = new ArrayList<>(list.size());
        for (OntologyEdge e : list) {
            EdgeRecord r = new EdgeRecord();
            r.setTenantId(e.getDataDomain() != null ? e.getDataDomain().getTenantId() : null);
            r.setSrc(e.getSrc());
            r.setSrcType(e.getSrcType());
            r.setP(e.getP());
            r.setDst(e.getDst());
            r.setDstType(e.getDstType());
            r.setInferred(e.isInferred());
            r.setProv(e.getProv());
            r.setTs(e.getTs() != null ? e.getTs() : new Date());
            out.add(r);
        }
        return out;
    }
}
