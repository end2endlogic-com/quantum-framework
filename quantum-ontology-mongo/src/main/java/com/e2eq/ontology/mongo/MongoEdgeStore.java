package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.DataDomainInfo;
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
 * Implements EdgeStore interface using DataDomainInfo but internally
 * converts to/from DataDomain for repository operations.
 */
@ApplicationScoped
public class MongoEdgeStore implements EdgeStore {

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Override
    public void upsert(DataDomainInfo dataDomainInfo, String srcType, String src, String p, String dstType, String dst, boolean inferred, Map<String, Object> prov) {
        DataDomain dd = DataDomainConverter.fromInfo(dataDomainInfo);
        edgeRepo.upsert(dd, srcType, src, p, dstType, dst, inferred, prov);
    }

    @Override
    public void upsertDerived(DataDomainInfo dataDomainInfo, String srcType, String src, String p, String dstType, String dst, List<EdgeRecord.Support> support, Map<String, Object> prov) {
        DataDomain dd = DataDomainConverter.fromInfo(dataDomainInfo);
        edgeRepo.upsertDerived(dd, srcType, src, p, dstType, dst, support, prov);
    }

    @Override
    public void upsertMany(Collection<EdgeRecord> edges) {
        if (edges == null || edges.isEmpty()) return;
        try {
            edgeRepo.bulkUpsertEdgeRecords(edges);
        } catch (Throwable t) {
            // Fallback to per-item upsert to preserve behavior if bulk fails for any reason
            for (EdgeRecord e : edges) {
                DataDomainInfo info = e.getDataDomainInfo();
                if (info == null) {
                    throw new IllegalArgumentException("EdgeRecord must have DataDomainInfo set");
                }
                if (e.isDerived()) {
                    upsertDerived(info, e.getSrcType(), e.getSrc(), e.getP(), e.getDstType(), e.getDst(), e.getSupport(), e.getProv());
                } else {
                    upsert(info, e.getSrcType(), e.getSrc(), e.getP(), e.getDstType(), e.getDst(), e.isInferred(), e.getProv());
                }
            }
        }
    }

    @Override
    public void deleteInferredBySrcNotIn(DataDomainInfo dataDomainInfo, String src, String p, Collection<String> dstKeep) {
        DataDomain dd = DataDomainConverter.fromInfo(dataDomainInfo);
        edgeRepo.deleteInferredBySrcNotIn(dd, src, p, dstKeep);
    }

    @Override
    public void deleteExplicitBySrcNotIn(DataDomainInfo dataDomainInfo, String src, String p, Collection<String> dstKeep) {
        DataDomain dd = DataDomainConverter.fromInfo(dataDomainInfo);
        edgeRepo.deleteExplicitBySrcNotIn(dd, src, p, dstKeep);
    }

    @Override
    public void deleteDerivedBySrcNotIn(DataDomainInfo dataDomainInfo, String src, String p, Collection<String> dstKeep) {
        DataDomain dd = DataDomainConverter.fromInfo(dataDomainInfo);
        edgeRepo.deleteDerivedBySrcNotIn(dd, src, p, dstKeep);
    }

    /**
     * Converts OntologyEdge to EdgeRecord, converting DataDomain to DataDomainInfo.
     */
    private static EdgeRecord toRecord(OntologyEdge e) {
        EdgeRecord r = new EdgeRecord();
        r.setDataDomainInfo(DataDomainConverter.toInfo(e.getDataDomain()));
        r.setSrc(e.getSrc());
        r.setSrcType(e.getSrcType());
        r.setP(e.getP());
        r.setDst(e.getDst());
        r.setDstType(e.getDstType());
        r.setInferred(e.isInferred());
        r.setDerived(e.isDerived());
        r.setProv(e.getProv());
        if (e.getSupport() != null) {
            List<EdgeRecord.Support> sup = e.getSupport().stream()
                .map(s -> new EdgeRecord.Support(s.getRuleId(), s.getPathEdgeIds()))
                .collect(Collectors.toList());
            r.setSupport(sup);
        }
        r.setTs(e.getTs() != null ? e.getTs() : new Date());
        return r;
    }

    @Override
    public List<EdgeRecord> findBySrc(DataDomainInfo dataDomainInfo, String src) {
        DataDomain dd = DataDomainConverter.fromInfo(dataDomainInfo);
        List<OntologyEdge> list = edgeRepo.findBySrc(dd, src);
        List<EdgeRecord> out = new ArrayList<>(list.size());
        for (OntologyEdge e : list) {
            out.add(toRecord(e));
        }
        return out;
    }

    @Override
    public List<EdgeRecord> listOutgoingBy(DataDomainInfo dataDomainInfo, String src, String p) {
        DataDomain dd = DataDomainConverter.fromInfo(dataDomainInfo);
        List<OntologyEdge> list = edgeRepo.findBySrcAndP(dd, src, p);
        List<EdgeRecord> out = new ArrayList<>(list.size());
        for (OntologyEdge e : list) out.add(toRecord(e));
        return out;
    }

    @Override
    public List<EdgeRecord> listIncomingBy(DataDomainInfo dataDomainInfo, String p, String dst) {
        DataDomain dd = DataDomainConverter.fromInfo(dataDomainInfo);
        List<OntologyEdge> list = edgeRepo.findByDstAndP(dd, dst, p);
        List<EdgeRecord> out = new ArrayList<>(list.size());
        for (OntologyEdge e : list) out.add(toRecord(e));
        return out;
    }

    @Override
    public void pruneDerivedWithoutSupport(DataDomainInfo dataDomainInfo) {
        DataDomain dd = DataDomainConverter.fromInfo(dataDomainInfo);
        edgeRepo.pruneDerivedWithoutSupport(dd);
    }
}
