package com.e2eq.ontology.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal in-memory EdgeStore implementation for unit tests.
 * Supports basic upserts and queries required by IncrementalChainEvaluator tests,
 * and counts listOutgoingBy queries per (src, p) to help asserting minimal queries.
 * 
 * Note: This simplified implementation ignores DataDomainInfo for scoping since
 * unit tests use a single logical domain. Production EdgeStore implementations
 * must properly scope by DataDomainInfo.
 */
public class InMemoryEdgeStoreTestDouble implements EdgeStore {

    private final List<EdgeRecord> allEdges = Collections.synchronizedList(new ArrayList<>());
    private final Map<NodePred, AtomicInteger> queryCounts = new ConcurrentHashMap<>();

    public int getQueryCount(String src, String p) {
        return queryCounts.getOrDefault(new NodePred(src, p), new AtomicInteger(0)).get();
    }

    @Override
    public void upsert(DataDomainInfo dataDomainInfo, String srcType, String src, String p, String dstType, String dst, boolean inferred, Map<String, Object> prov) {
        EdgeRecord rec = new EdgeRecord(dataDomainInfo, srcType, src, p, dstType, dst, inferred, prov != null ? prov : Map.of(), new Date());
        rec.setDerived(inferred);
        addOrReplace(rec);
    }

    @Override
    public void upsertDerived(DataDomainInfo dataDomainInfo, String srcType, String src, String p, String dstType, String dst, List<EdgeRecord.Support> support, Map<String, Object> prov) {
        EdgeRecord rec = new EdgeRecord(dataDomainInfo, srcType, src, p, dstType, dst, true, prov != null ? prov : Map.of(), support, new Date());
        rec.setDerived(true);
        rec.setInferred(true);
        addOrReplace(rec);
    }

    @Override
    public void upsertMany(Collection<EdgeRecord> edges) {
        if (edges == null) return;
        for (EdgeRecord e : edges) addOrReplace(e);
    }

    private void addOrReplace(EdgeRecord rec) {
        synchronized (allEdges) {
            // replace by unique key (src, p, dst) - ignoring dataDomainInfo for unit tests
            int idx = -1;
            for (int i = 0; i < allEdges.size(); i++) {
                EdgeRecord e = allEdges.get(i);
                if (Objects.equals(e.getSrc(), rec.getSrc()) && Objects.equals(e.getP(), rec.getP()) && Objects.equals(e.getDst(), rec.getDst())) {
                    idx = i; break;
                }
            }
            if (idx >= 0) allEdges.set(idx, rec); else allEdges.add(rec);
        }
    }

    @Override
    public List<EdgeRecord> findBySrc(DataDomainInfo dataDomainInfo, String src) {
        List<EdgeRecord> out = new ArrayList<>();
        synchronized (allEdges) {
            for (EdgeRecord e : allEdges) if (Objects.equals(e.getSrc(), src)) out.add(copy(e));
        }
        return out;
    }

    @Override
    public List<EdgeRecord> listOutgoingBy(DataDomainInfo dataDomainInfo, String src, String p) {
        queryCounts.computeIfAbsent(new NodePred(src, p), k -> new AtomicInteger()).incrementAndGet();
        List<EdgeRecord> out = new ArrayList<>();
        synchronized (allEdges) {
            for (EdgeRecord e : allEdges) if (Objects.equals(e.getSrc(), src) && Objects.equals(e.getP(), p)) out.add(copy(e));
        }
        return out;
    }

    @Override
    public List<EdgeRecord> listIncomingBy(DataDomainInfo dataDomainInfo, String p, String dst) {
        List<EdgeRecord> out = new ArrayList<>();
        synchronized (allEdges) {
            for (EdgeRecord e : allEdges) if (Objects.equals(e.getDst(), dst) && Objects.equals(e.getP(), p)) out.add(copy(e));
        }
        return out;
    }

    @Override
    public void deleteInferredBySrcNotIn(DataDomainInfo dataDomainInfo, String src, String p, Collection<String> dstKeep) {
        // Not needed for these unit tests.
    }

    @Override
    public void deleteExplicitBySrcNotIn(DataDomainInfo dataDomainInfo, String src, String p, Collection<String> dstKeep) {
        // Not needed for these unit tests.
    }

    @Override
    public void deleteDerivedBySrcNotIn(DataDomainInfo dataDomainInfo, String src, String p, Collection<String> dstKeep) {
        // Not needed for these unit tests.
    }

    @Override
    public void pruneDerivedWithoutSupport(DataDomainInfo dataDomainInfo) {
        // Not needed for tests.
    }

    private static EdgeRecord copy(EdgeRecord e) {
        EdgeRecord r = new EdgeRecord();
        r.setDataDomainInfo(e.getDataDomainInfo());
        r.setSrc(e.getSrc());
        r.setSrcType(e.getSrcType());
        r.setP(e.getP());
        r.setDst(e.getDst());
        r.setDstType(e.getDstType());
        r.setInferred(e.isInferred());
        r.setDerived(e.isDerived());
        r.setProv(e.getProv());
        r.setSupport(e.getSupport());
        r.setTs(e.getTs());
        return r;
    }

    private record NodePred(String src, String p) {}
}
