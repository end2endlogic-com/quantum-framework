
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
        //
        // CYCLE DETECTION: We track visited nodes to prevent infinite loops in cyclic graphs (A→B→C→A).
        // We also limit the depth of neighbor expansion to prevent excessive traversal.
        List<Reasoner.Edge> extendedEdges = new ArrayList<>(explicitEdges != null ? explicitEdges : List.of());
        if (explicitEdges != null && !registry.propertyChains().isEmpty()) {
            // Track visited nodes to detect cycles: start with the source entity
            Set<String> visitedNodes = new HashSet<>();
            visitedNodes.add(entityId); // Don't fetch edges back to the source entity

            // Determine max chain length to limit neighbor expansion depth
            int maxChainLength = registry.propertyChains().stream()
                    .mapToInt(ch -> ch.chain() != null ? ch.chain().size() : 0)
                    .max()
                    .orElse(2);

            // BFS-style expansion with depth limit to support multi-hop chains
            // while preventing cycles
            Set<String> currentFrontier = new HashSet<>();
            for (var edge : explicitEdges) {
                currentFrontier.add(edge.dstId());
            }

            for (int depth = 0; depth < maxChainLength - 1 && !currentFrontier.isEmpty(); depth++) {
                Set<String> nextFrontier = new HashSet<>();

                for (String neighborId : currentFrontier) {
                    // Skip if already visited (cycle detection)
                    if (!visitedNodes.add(neighborId)) {
                        continue;
                    }

                    // Fetch neighbor's outgoing edges from the edge repo
                    List<OntologyEdge> neighborEdges = edgeRepo.findBySrc(realmId, dataDomain, neighborId);
                    for (OntologyEdge ne : neighborEdges) {
                        // Convert OntologyEdge to Reasoner.Edge
                        extendedEdges.add(new Reasoner.Edge(
                                ne.getSrc(), ne.getSrcType(), ne.getP(),
                                ne.getDst(), ne.getDstType(), ne.isInferred(),
                                Optional.empty()
                        ));

                        // Add destination to next frontier for deeper expansion
                        // (only if not already visited - cycle detection)
                        if (!visitedNodes.contains(ne.getDst())) {
                            nextFrontier.add(ne.getDst());
                        }
                    }
                }

                currentFrontier = nextFrontier;
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

    // ========================================================================
    // Bulk Operations for Reindex Performance
    // ========================================================================

    /**
     * Holds the context for a single entity's edges to be bulk-processed.
     */
    public record EntityEdgeContext(
            String realmId,
            DataDomain dataDomain,
            String entityId,
            String entityType,
            List<Reasoner.Edge> explicitEdges
    ) {}

    /**
     * Apply materialization for multiple entities in bulk, using batched database operations.
     * This significantly improves performance for reindex operations by reducing DB round-trips.
     * Skips edges that already exist and haven't changed to avoid unnecessary writes.
     *
     * @param entities list of entity edge contexts to process
     * @return combined EdgeChanges for all entities
     */
    public EdgeChanges applyBulk(List<EntityEdgeContext> entities) {
        if (entities == null || entities.isEmpty()) {
            return EdgeChanges.empty();
        }

        EdgeChanges allChanges = new EdgeChanges();
        String realmId = entities.get(0).realmId();

        // Phase 0: Fetch ALL existing edges for all entities in the batch upfront
        // Build a lookup map: entityId -> (src|p|dst) -> OntologyEdge
        Map<String, Map<String, OntologyEdge>> existingEdgesByEntity = new HashMap<>();
        for (EntityEdgeContext ctx : entities) {
            if (ctx.dataDomain() == null) continue;
            List<OntologyEdge> existing = edgeRepo.findBySrc(realmId, ctx.dataDomain(), ctx.entityId());
            Map<String, OntologyEdge> edgeMap = new HashMap<>();
            for (OntologyEdge e : existing) {
                String key = e.getSrc() + "|" + e.getP() + "|" + e.getDst();
                edgeMap.put(key, e);
            }
            existingEdgesByEntity.put(ctx.entityId(), edgeMap);
        }

        // Collect only NEW or CHANGED edges to upsert
        List<EdgeRecord> edgesToUpsert = new ArrayList<>();

        // Track what we're adding per entity for pruning phase
        Map<String, Map<String, Set<String>>> entityExplicitByP = new HashMap<>();  // entityId -> predicate -> dstIds
        Map<String, Map<String, Set<String>>> entityComputedByP = new HashMap<>();
        Map<String, Map<String, Set<String>>> entityInferredByP = new HashMap<>();

        int skippedCount = 0;

        // Phase 1: Collect edges, comparing against existing to skip unchanged
        for (EntityEdgeContext ctx : entities) {
            if (ctx.dataDomain() == null) {
                continue;
            }

            DataDomain dataDomain = ctx.dataDomain();
            String entityId = ctx.entityId();
            String entityType = ctx.entityType();
            List<Reasoner.Edge> explicitEdges = ctx.explicitEdges();

            OntologyRegistry registry = registryProvider.getRegistryForTenant(dataDomain);
            DataDomainInfo dataDomainInfo = DataDomainConverter.toInfo(dataDomain);
            Map<String, OntologyEdge> existingForEntity = existingEdgesByEntity.getOrDefault(entityId, Map.of());

            // Build extended edges for chain inference (same logic as single apply)
            List<Reasoner.Edge> extendedEdges = new ArrayList<>(explicitEdges != null ? explicitEdges : List.of());
            if (explicitEdges != null && !registry.propertyChains().isEmpty()) {
                Set<String> visitedNodes = new HashSet<>();
                visitedNodes.add(entityId);
                int maxChainLength = registry.propertyChains().stream()
                        .mapToInt(ch -> ch.chain() != null ? ch.chain().size() : 0)
                        .max().orElse(2);
                Set<String> currentFrontier = new HashSet<>();
                for (var edge : explicitEdges) {
                    currentFrontier.add(edge.dstId());
                }
                for (int depth = 0; depth < maxChainLength - 1 && !currentFrontier.isEmpty(); depth++) {
                    Set<String> nextFrontier = new HashSet<>();
                    for (String neighborId : currentFrontier) {
                        if (!visitedNodes.add(neighborId)) continue;
                        List<OntologyEdge> neighborEdges = edgeRepo.findBySrc(realmId, dataDomain, neighborId);
                        for (OntologyEdge ne : neighborEdges) {
                            extendedEdges.add(new Reasoner.Edge(
                                    ne.getSrc(), ne.getSrcType(), ne.getP(),
                                    ne.getDst(), ne.getDstType(), ne.isInferred(),
                                    Optional.empty()
                            ));
                            if (!visitedNodes.contains(ne.getDst())) {
                                nextFrontier.add(ne.getDst());
                            }
                        }
                    }
                    currentFrontier = nextFrontier;
                }
            }

            // Run reasoner to get inferred edges
            var snap = new Reasoner.EntitySnapshot(dataDomain.getTenantId(), entityId, entityType, extendedEdges);
            var out = reasoner.infer(snap, registry);

            // Collect explicit and computed edges - only if new or changed
            Map<String, Set<String>> explicitByP = new HashMap<>();
            Map<String, Set<String>> computedByP = new HashMap<>();
            if (explicitEdges != null) {
                for (var e : explicitEdges) {
                    boolean isComputed = e.prov().map(p -> "computed".equals(p.rule())).orElse(false);
                    Map<String, Object> prov = e.prov()
                            .map(p -> Map.<String, Object>of("rule", p.rule(), "inputs", p.inputs()))
                            .orElse(Map.of());

                    // Track for pruning regardless of whether we upsert
                    if (isComputed) {
                        computedByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                    } else {
                        explicitByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                    }

                    // Check if edge already exists with same properties
                    String edgeKey = e.srcId() + "|" + e.p() + "|" + e.dstId();
                    OntologyEdge existing = existingForEntity.get(edgeKey);
                    if (existing != null) {
                        // Check if unchanged
                        boolean sameInferred = existing.isInferred() == false;
                        boolean sameDerived = Objects.equals(existing.isDerived(), isComputed);
                        boolean sameProv = Objects.equals(existing.getProv(), prov);
                        if (sameInferred && sameDerived && sameProv) {
                            skippedCount++;
                            continue; // Skip - edge already exists and is unchanged
                        }
                    }

                    // Edge is new or changed - add to upsert list
                    EdgeRecord rec = new EdgeRecord();
                    rec.setDataDomainInfo(dataDomainInfo);
                    rec.setSrc(e.srcId());
                    rec.setSrcType(e.srcType());
                    rec.setP(e.p());
                    rec.setDst(e.dstId());
                    rec.setDstType(e.dstType());
                    rec.setProv(prov);
                    rec.setTs(new Date());
                    rec.setDerived(isComputed);
                    rec.setInferred(false);
                    edgesToUpsert.add(rec);

                    if (existing == null) {
                        allChanges.added().add(rec);
                    } else {
                        allChanges.modified().add(rec);
                    }
                }
            }
            entityExplicitByP.put(entityId, explicitByP);
            entityComputedByP.put(entityId, computedByP);

            // Collect inferred edges - only if new or changed
            Map<String, Set<String>> inferredByP = new HashMap<>();
            for (var e : out.addEdges()) {
                inferredByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                Map<String, Object> prov = e.prov().map(p -> Map.<String, Object>of(
                        "rule", p.rule(),
                        "inputs", p.inputs()
                )).orElse(Map.of());

                // Check if edge already exists with same properties
                String edgeKey = e.srcId() + "|" + e.p() + "|" + e.dstId();
                OntologyEdge existing = existingForEntity.get(edgeKey);
                if (existing != null) {
                    boolean sameInferred = existing.isInferred() == true;
                    boolean sameProv = Objects.equals(existing.getProv(), prov);
                    if (sameInferred && sameProv) {
                        skippedCount++;
                        continue; // Skip - edge already exists and is unchanged
                    }
                }

                EdgeRecord rec = new EdgeRecord(dataDomainInfo, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), true, prov, new Date());
                edgesToUpsert.add(rec);

                if (existing == null) {
                    allChanges.added().add(rec);
                } else {
                    allChanges.modified().add(rec);
                }
            }
            entityInferredByP.put(entityId, inferredByP);
        }

        // Phase 2: Bulk upsert only new/changed edges
        if (!edgesToUpsert.isEmpty()) {
            edgeRepo.bulkUpsertEdgeRecords(realmId, edgesToUpsert);
        }

        // Phase 3: Prune stale edges for each entity
        for (EntityEdgeContext ctx : entities) {
            if (ctx.dataDomain() == null) continue;

            String entityId = ctx.entityId();
            DataDomain dataDomain = ctx.dataDomain();

            Map<String, Set<String>> explicitByP = entityExplicitByP.getOrDefault(entityId, Map.of());
            Map<String, Set<String>> computedByP = entityComputedByP.getOrDefault(entityId, Map.of());
            Map<String, Set<String>> inferredByP = entityInferredByP.getOrDefault(entityId, Map.of());

            // Use cached existing edges
            Map<String, OntologyEdge> existingMap = existingEdgesByEntity.getOrDefault(entityId, Map.of());

            // Collect predicates by type from existing edges
            Set<String> existingExplicitPreds = new HashSet<>();
            Set<String> existingComputedPreds = new HashSet<>();
            Set<String> existingInferredPreds = new HashSet<>();

            for (OntologyEdge e : existingMap.values()) {
                if (e.isInferred()) {
                    existingInferredPreds.add(e.getP());
                } else if (Boolean.TRUE.equals(e.isDerived())) {
                    existingComputedPreds.add(e.getP());
                } else {
                    existingExplicitPreds.add(e.getP());
                }
            }

            // Prune explicit edges
            for (String p : existingExplicitPreds) {
                Set<String> keep = explicitByP.getOrDefault(p, Set.of());
                edgeRepo.deleteExplicitBySrcNotIn(realmId, dataDomain, entityId, p, keep);
            }

            // Prune computed edges
            for (String p : existingComputedPreds) {
                Set<String> keep = computedByP.getOrDefault(p, Set.of());
                edgeRepo.deleteDerivedBySrcNotIn(realmId, dataDomain, entityId, p, keep);
            }

            // Prune inferred edges
            for (String p : existingInferredPreds) {
                Set<String> keep = inferredByP.getOrDefault(p, Set.of());
                edgeRepo.deleteInferredBySrcNotIn(realmId, dataDomain, entityId, p, keep);
            }
        }

        return allChanges;
    }
}
