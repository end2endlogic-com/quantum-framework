
package com.e2eq.ontology.mongo;

import java.util.*;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.EdgeRecord;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import jakarta.inject.Inject;
import com.e2eq.ontology.core.*;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Materializes ontology edges for entities, including both explicit and inferred edges.
 * All operations are scoped by DataDomain to ensure isolation across organizations,
 * accounts, tenants, and data segments.
 */
@ApplicationScoped
public class OntologyMaterializer {

   @Inject
   protected ForwardChainingReasoner reasoner;

   @Inject
   protected com.e2eq.ontology.runtime.TenantOntologyRegistryProvider registryProvider;

   @Inject
   protected OntologyEdgeRepo edgeRepo;

    /**
     * Apply materialization for an entity with full DataDomain scoping.
     *
     * @param dataDomain  full DataDomain context for edge scoping
     * @param entityId    ID of the entity
     * @param entityType  type of the entity
     * @param explicitEdges list of explicit edges from the entity
     * @return EdgeChanges summary of changes made
     */
    public EdgeChanges apply(DataDomain dataDomain, String entityId, String entityType, List<Reasoner.Edge> explicitEdges) {
        return apply(null, dataDomain, entityId, entityType, explicitEdges);
    }

    public EdgeChanges apply(String realmId, DataDomain dataDomain, String entityId, String entityType, List<Reasoner.Edge> explicitEdges) {
        if (dataDomain == null) {
            throw new IllegalArgumentException("DataDomain must be provided for materialization");
        }
        EdgeChanges changes = new EdgeChanges();

        String tenantId = dataDomain.getTenantId(); // For logging/reasoner compatibility

        // Resolve registry for the given DataDomain
        OntologyRegistry registry = registryProvider.getRegistryForTenant(dataDomain);

        // Build an extended snapshot that includes neighbor edges for chain inference.
        // For each explicit edge A->B, fetch B's outgoing edges so chains like [refersToB, refersToC]
        // can be evaluated to produce A->C inferred edges.
        List<Reasoner.Edge> extendedEdges = new ArrayList<>(explicitEdges != null ? explicitEdges : List.of());
        if (explicitEdges != null && !registry.propertyChains().isEmpty()) {
            Set<String> visitedNeighbors = new HashSet<>();
            for (var edge : explicitEdges) {
                String neighborId = edge.dstId();
                if (visitedNeighbors.add(neighborId)) {
                    // Fetch neighbor's outgoing edges from the edge repo
                    List<OntologyEdge> neighborEdges = edgeRepo.findBySrc(realmId, dataDomain, neighborId);
                    for (OntologyEdge ne : neighborEdges) {
                        // Convert OntologyEdge to Reasoner.Edge
                        extendedEdges.add(new Reasoner.Edge(
                                ne.getSrc(), ne.getSrcType(), ne.getP(),
                                ne.getDst(), ne.getDstType(), ne.isInferred(),
                                Optional.empty()
                        ));
                    }
                }
            }
        }

        var snap = new Reasoner.EntitySnapshot(tenantId, entityId, entityType, extendedEdges);
        var out = reasoner.infer(snap, registry);

        List<OntologyEdge> existingAll = edgeRepo.findBySrc(realmId, dataDomain, entityId);

        // 1) Upsert EXPLICIT edges as-is for traversal and cascade logic
        // Separate computed edges (from ComputedEdgeProvider) from regular explicit edges
        Map<String, Set<String>> explicitByP = new HashMap<>();
        Map<String, Set<String>> computedByP = new HashMap<>();
        if (explicitEdges != null) {
            for (var e : explicitEdges) {
                // Check if this is a computed edge (from ComputedEdgeProvider)
                boolean isComputed = e.prov().map(p -> "computed".equals(p.rule())).orElse(false);

                Map<String, Object> prov = e.prov()
                        .map(p -> Map.<String, Object>of("rule", p.rule(), "inputs", p.inputs()))
                        .orElse(Map.of());

                OntologyEdge existing = existingAll.stream()
                        .filter(ex -> Objects.equals(ex.getP(), e.p()) && Objects.equals(ex.getDst(), e.dstId()))
                        .findFirst().orElse(null);

                if (isComputed) {
                    // Computed edges are tracked separately and stored as derived
                    computedByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                    // Use upsertDerived for computed edges so they get derived=true flag
                    edgeRepo.upsertDerived(realmId, dataDomain, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), List.of(), prov);

                    OntologyEdge updated = findEdge(realmId, dataDomain, e.srcId(), e.p(), e.dstId());
                    if (existing == null) {
                        changes.added().add(toEdgeRecord(updated, dataDomain));
                    } else if (isModified(existing, updated)) {
                        changes.modified().add(toEdgeRecord(updated, dataDomain));
                    }
                } else {
                    // Regular explicit edges
                    explicitByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                    edgeRepo.upsert(realmId, dataDomain, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), false, prov);

                    OntologyEdge updated = findEdge(realmId, dataDomain, e.srcId(), e.p(), e.dstId());
                    if (existing == null) {
                        changes.added().add(toEdgeRecord(updated, dataDomain));
                    } else if (isModified(existing, updated)) {
                        changes.modified().add(toEdgeRecord(updated, dataDomain));
                    }
                }
            }
        }

        // 2) Collect new INFERRED edges by predicate
        Map<String, Set<String>> newByP = new HashMap<>();
        List<EdgeRecord> upserts = new ArrayList<>();
        DataDomainInfo dataDomainInfo = DataDomainConverter.toInfo(dataDomain);
        for (var e : out.addEdges()) {
            newByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
            Map<String,Object> prov = e.prov().map(p -> Map.<String,Object>of(
                    "rule", p.rule(),
                    "inputs", p.inputs()
            )).orElse(Map.of());

            EdgeRecord rec = new EdgeRecord(dataDomainInfo, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), true, prov, new Date());
            upserts.add(rec);

            // Check if inferred edge is already present for change tracking
            OntologyEdge existing = existingAll.stream()
                    .filter(ex -> Objects.equals(ex.getP(), e.p()) && Objects.equals(ex.getDst(), e.dstId()))
                    .findFirst().orElse(null);

            if (existing == null) {
                changes.added().add(rec);
            } else if (isModifiedRecord(existing, rec)) {
                changes.modified().add(rec);
            }
        }
        if (!upserts.isEmpty()) {
            edgeRepo.bulkUpsertEdgeRecords(realmId, upserts);
        }

        // 3) Prune inferred edges no longer justified
        List<OntologyEdge> existingAfterUpsert = edgeRepo.findBySrc(realmId, dataDomain, entityId);
        Map<String, Set<String>> existingInfByP = new HashMap<>();
        for (OntologyEdge e : existingAfterUpsert) {
            if (!e.isInferred()) continue;
            existingInfByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
        }
        for (Map.Entry<String, Set<String>> en : existingInfByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = newByP.getOrDefault(p, Set.of());

            for (String dst : en.getValue()) {
                if (!keep.contains(dst)) {
                    existingAfterUpsert.stream()
                            .filter(ex -> Objects.equals(ex.getP(), p) && Objects.equals(ex.getDst(), dst) && ex.isInferred())
                            .findFirst().ifPresent(ex -> changes.removed().add(toEdgeRecord(ex, dataDomain)));
                }
            }

            // delete inferred edges not in keep for predicate p
            edgeRepo.deleteInferredBySrcNotIn(realmId, dataDomain, entityId, p, keep);
        }

        // 4) Prune explicit edges that are no longer present in the entity snapshot
        // Determine all predicates that currently exist for this source
        Set<String> existingExplicitPreds = new HashSet<>();
        for (OntologyEdge e : existingAfterUpsert) {
            if (!e.isInferred() && !Boolean.TRUE.equals(e.isDerived())) existingExplicitPreds.add(e.getP());
        }
        // For predicates present in snapshot: keep only dsts that remain
        for (Map.Entry<String, Set<String>> en : explicitByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = en.getValue();

            existingAfterUpsert.stream()
                    .filter(ex -> Objects.equals(ex.getP(), p) && !ex.isInferred() && !Boolean.TRUE.equals(ex.isDerived()) && !keep.contains(ex.getDst()))
                    .forEach(ex -> changes.removed().add(toEdgeRecord(ex, dataDomain)));

            edgeRepo.deleteExplicitBySrcNotIn(realmId, dataDomain, entityId, p, keep);
            existingExplicitPreds.remove(p);
        }
        // For predicates absent now: remove all explicit edges for those predicates
        for (String pAbsent : existingExplicitPreds) {
            existingAfterUpsert.stream()
                    .filter(ex -> Objects.equals(ex.getP(), pAbsent) && !ex.isInferred() && !Boolean.TRUE.equals(ex.isDerived()))
                    .forEach(ex -> changes.removed().add(toEdgeRecord(ex, dataDomain)));
            edgeRepo.deleteExplicitBySrcNotIn(realmId, dataDomain, entityId, pAbsent, Set.of());
        }

        // 5) Prune derived/computed edges that are no longer present in the provider output
        // Determine existing derived predicates
        Map<String, Set<String>> existingDerivedByP = new HashMap<>();
        for (OntologyEdge e : existingAfterUpsert) {
            if (Boolean.TRUE.equals(e.isDerived()) && !e.isInferred()) { // derived but not inferred = computed edges
                existingDerivedByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
            }
        }
        // For predicates present in computed snapshot: keep only dsts that remain
        for (Map.Entry<String, Set<String>> en : computedByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = en.getValue();

            existingAfterUpsert.stream()
                    .filter(ex -> Objects.equals(ex.getP(), p) && Boolean.TRUE.equals(ex.isDerived()) && !ex.isInferred() && !keep.contains(ex.getDst()))
                    .forEach(ex -> changes.removed().add(toEdgeRecord(ex, dataDomain)));

            edgeRepo.deleteDerivedBySrcNotIn(realmId, dataDomain, entityId, p, keep);
            existingDerivedByP.remove(p);
        }
        // For predicates with no computed edges now: remove all derived edges for those predicates
        for (String pAbsent : existingDerivedByP.keySet()) {
            existingAfterUpsert.stream()
                    .filter(ex -> Objects.equals(ex.getP(), pAbsent) && Boolean.TRUE.equals(ex.isDerived()) && !ex.isInferred())
                    .forEach(ex -> changes.removed().add(toEdgeRecord(ex, dataDomain)));
            edgeRepo.deleteDerivedBySrcNotIn(realmId, dataDomain, entityId, pAbsent, Set.of());
        }

        return changes;
    }

    /**
     * Find a specific edge by its key (src, p, dst) within the DataDomain.
     */
    private OntologyEdge findEdge(String realmId, DataDomain dataDomain, String src, String p, String dst) {
        List<OntologyEdge> edges = edgeRepo.findBySrcAndP(realmId, dataDomain, src, p);
        return edges.stream()
                .filter(e -> Objects.equals(e.getDst(), dst))
                .findFirst()
                .orElse(null);
    }

    /**
     * Convert OntologyEdge to EdgeRecord for change tracking.
     */
    private EdgeRecord toEdgeRecord(OntologyEdge e, DataDomain dataDomain) {
        if (e == null) return null;
        DataDomainInfo ddi = DataDomainConverter.toInfo(dataDomain);
        EdgeRecord rec = new EdgeRecord();
        rec.setDataDomainInfo(ddi);
        rec.setSrc(e.getSrc());
        rec.setSrcType(e.getSrcType());
        rec.setP(e.getP());
        rec.setDst(e.getDst());
        rec.setDstType(e.getDstType());
        rec.setInferred(e.isInferred());
        rec.setDerived(Boolean.TRUE.equals(e.isDerived()));
        rec.setProv(e.getProv());
        if (e.getSupport() != null) {
            List<EdgeRecord.Support> sup = new ArrayList<>();
            for (OntologyEdge.Support s : e.getSupport()) {
                sup.add(new EdgeRecord.Support(s.getRuleId(), s.getPathEdgeIds()));
            }
            rec.setSupport(sup);
        }
        rec.setTs(e.getTs() != null ? e.getTs() : new Date());
        return rec;
    }

    private boolean isModified(OntologyEdge existing, OntologyEdge updated) {
        if (existing == null || updated == null) return existing != updated;
        if (existing.isInferred() != updated.isInferred()) return true;
        if (!Objects.equals(existing.isDerived(), updated.isDerived())) return true;
        if (!Objects.equals(existing.getProv(), updated.getProv())) return true;
        if (!Objects.equals(existing.getSupport(), updated.getSupport())) return true;
        return false;
    }

    private boolean isModifiedRecord(OntologyEdge existing, EdgeRecord updated) {
        if (existing == null || updated == null) return existing != null || updated != null;
        if (existing.isInferred() != updated.isInferred()) return true;
        if (!Objects.equals(existing.isDerived(), updated.isDerived())) return true;
        if (!Objects.equals(existing.getProv(), updated.getProv())) return true;
        return false;
    }
}
