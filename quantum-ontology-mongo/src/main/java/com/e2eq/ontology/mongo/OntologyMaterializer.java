
package com.e2eq.ontology.mongo;

import java.util.*;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.EdgeRecord;
import com.e2eq.ontology.metrics.OntologyMetrics;
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

   @Inject
   protected OntologyMetrics metrics;

    /**
     * Extract a provider id from a Reasoner.Edge's provenance, or null if the
     * edge was not produced by a {@code ComputedEdgeProvider}.
     */
    private static String providerIdOf(Reasoner.Edge e) {
        return e.prov()
                .filter(p -> "computed".equals(p.rule()))
                .map(p -> {
                    Object v = p.inputs() == null ? null : p.inputs().get("providerId");
                    return v == null ? null : v.toString();
                })
                .orElse(null);
    }

    /**
     * Extract a provider id from a stored OntologyEdge's provenance.
     */
    private static String providerIdOf(OntologyEdge e) {
        if (e == null || e.getProv() == null) return null;
        Object v = e.getProv().get("providerId");
        return v == null ? null : v.toString();
    }

    /** An entity left out of a bulk materialization because its explicit edges were refused. */
    public record RejectedEntity(String entityId, String entityType, String message) {}

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
        // Refuse explicit edges for stable derived relations (relation contract, P1) before anything
        // is written; computed-provider edges are exempt
        StableRelationGuard.requireExplicitEdgesAllowed(registry, explicitEdges);

        List<OntologyEdge> existingAll = edgeRepo.findBySrc(realmId, dataDomain, entityId);
        List<Reasoner.Edge> foreign = ownedElsewhere(existingAll, entityId, explicitEdges);
        List<Reasoner.Edge> base = new ArrayList<>(explicitEdges != null ? explicitEdges : List.of());
        base.addAll(foreign);

        List<Reasoner.Edge> extendedEdges = chainSnapshotEdges(realmId, dataDomain, entityId, base, registry);
        var snap = new Reasoner.EntitySnapshot(tenantId, entityId, entityType, extendedEdges);
        var out = withoutImagesOf(reasoner.infer(snap, registry), foreign);

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
                        String pid = providerIdOf(e);
                        if (pid != null) metrics.recordEdgesAdded(pid, 1);
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

        // 2) + 3) Upsert new INFERRED edges and prune inferred edges no longer justified
        List<OntologyEdge> existingAfterUpsert = upsertAndPruneInferred(realmId, dataDomain, entityId, out, existingAll,
                assertedKeys(existingAll, explicitEdges), changes);

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
                    .forEach(ex -> {
                        changes.removed().add(toEdgeRecord(ex, dataDomain));
                        String pid = providerIdOf(ex);
                        if (pid != null) metrics.recordEdgesRemoved(pid, 1);
                    });

            edgeRepo.deleteDerivedBySrcNotIn(realmId, dataDomain, entityId, p, keep);
            existingDerivedByP.remove(p);
        }
        // For predicates with no computed edges now: remove all derived edges for those predicates
        for (String pAbsent : existingDerivedByP.keySet()) {
            existingAfterUpsert.stream()
                    .filter(ex -> Objects.equals(ex.getP(), pAbsent) && Boolean.TRUE.equals(ex.isDerived()) && !ex.isInferred())
                    .forEach(ex -> {
                        changes.removed().add(toEdgeRecord(ex, dataDomain));
                        String pid = providerIdOf(ex);
                        if (pid != null) metrics.recordEdgesRemoved(pid, 1);
                    });
            edgeRepo.deleteDerivedBySrcNotIn(realmId, dataDomain, entityId, pAbsent, Set.of());
        }

        // 6) A dropped explicit edge may still be the image of the other end's own edge (B peerOf A
        // implies A peerOf B): re-derive that end so its image comes back
        rederiveMirrors(realmId, dataDomain, entityId, registry, changes);

        return changes;
    }

    /**
     * Re-derive the far end of every explicit edge {@code entityId} dropped whose predicate has an
     * inverse or is symmetric, merging the changes. An image is never written over an explicit edge,
     * so once the explicit edge is gone the other end has to write its image again.
     */
    private void rederiveMirrors(String realmId, DataDomain dataDomain, String entityId, OntologyRegistry registry,
                                 EdgeChanges changes) {
        Map<String, String> ends = new LinkedHashMap<>();
        for (EdgeRecord rec : List.copyOf(changes.removed())) {
            if (rec.isInferred() || !Objects.equals(rec.getSrc(), entityId) || Objects.equals(rec.getDst(), entityId)) continue;
            if (mirrored(registry, rec.getP())) ends.putIfAbsent(rec.getDst(), rec.getDstType());
        }
        for (Map.Entry<String, String> end : ends.entrySet()) {
            EdgeChanges more = rederive(realmId, dataDomain, end.getKey(), end.getValue());
            changes.added().addAll(more.added());
            changes.modified().addAll(more.modified());
            changes.removed().addAll(more.removed());
        }
    }

    /** Whether edges of {@code p} have an image at the other end: it is symmetric or has an inverse. */
    private static boolean mirrored(OntologyRegistry registry, String p) {
        Optional<OntologyRegistry.PropertyDef> def = registry.propertyOf(p);
        if (def.isPresent() && (def.get().symmetric() || def.get().inverseOf().isPresent())) return true;
        return registry.properties().values().stream()
                .anyMatch(q -> q.inverseOf().isPresent() && q.inverseOf().get().equals(p));
    }

    /**
     * The snapshot a chain is evaluated over: the entity's own base edges plus, for each hop a
     * chain can take, the outgoing edges of the nodes they reach.
     */
    private List<Reasoner.Edge> chainSnapshotEdges(String realmId, DataDomain dataDomain, String entityId,
                                                   List<Reasoner.Edge> baseEdges, OntologyRegistry registry) {
        // Build an extended snapshot that includes neighbor edges for chain inference.
        // For each explicit edge A->B, fetch B's outgoing edges so chains like [refersToB, refersToC]
        // can be evaluated to produce A->C inferred edges.
        //
        // CYCLE DETECTION: We track visited nodes to prevent infinite loops in cyclic graphs (A→B→C→A).
        // We also limit the depth of neighbor expansion to prevent excessive traversal.
        List<Reasoner.Edge> extendedEdges = new ArrayList<>(baseEdges != null ? baseEdges : List.of());
        if (baseEdges != null && !registry.propertyChains().isEmpty()) {
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
            for (var edge : baseEdges) {
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

        return extendedEdges;
    }

    /**
     * Upsert the reasoner's inferred edges for {@code entityId} and delete its inferred edges that
     * are no longer justified. Returns the entity's edges after the upsert, before pruning.
     */
    private List<OntologyEdge> upsertAndPruneInferred(String realmId, DataDomain dataDomain, String entityId,
                                                      Reasoner.InferenceResult out, List<OntologyEdge> existingAll,
                                                      Set<List<String>> asserted, EdgeChanges changes) {
        // 2) Collect new INFERRED edges. Most leave the entity; inverse and symmetric images of its
        // edges land on the other node and are owned by this entity all the same.
        Map<String, Set<String>> newByP = new HashMap<>();
        Set<List<String>> newElsewhere = new HashSet<>();
        List<EdgeRecord> upserts = new ArrayList<>();
        DataDomainInfo dataDomainInfo = DataDomainConverter.toInfo(dataDomain);
        for (var e : out.addEdges()) {
            boolean own = Objects.equals(e.srcId(), entityId);
            OntologyEdge existing;
            if (own) {
                newByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                // Asserted or computed here (the reasoner mirrors neighbour edges back): leave it as it is
                if (asserted.contains(List.of(e.srcId(), e.p(), e.dstId()))) continue;
                existing = existingAll.stream()
                        .filter(ex -> Objects.equals(ex.getP(), e.p()) && Objects.equals(ex.getDst(), e.dstId()))
                        .findFirst().orElse(null);
            } else {
                newElsewhere.add(List.of(e.srcId(), e.p(), e.dstId()));
                existing = findEdge(realmId, dataDomain, e.srcId(), e.p(), e.dstId());
                // The other node asserts (or computes) this edge itself: leave it as it is
                if (existing != null && !existing.isInferred()) continue;
            }
            Map<String,Object> prov = e.prov().map(p -> Map.<String,Object>of(
                    "rule", p.rule(),
                    "inputs", p.inputs()
            )).orElse(Map.of());

            EdgeRecord rec = new EdgeRecord(dataDomainInfo, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), true, prov, new Date());
            upserts.add(rec);

            if (existing == null) {
                changes.added().add(rec);
            } else if (isModifiedRecord(existing, rec)) {
                changes.modified().add(rec);
            }
        }
        if (!upserts.isEmpty()) {
            edgeRepo.bulkUpsertEdgeRecords(realmId, upserts);
        }

        // 3) Prune inferred edges no longer justified. Inverse and symmetric edges at the entity that
        // another node's edge produced belong to that node and are left alone.
        List<OntologyEdge> existingAfterUpsert = edgeRepo.findBySrc(realmId, dataDomain, entityId);
        Map<String, Set<String>> existingInfByP = new HashMap<>();
        Map<String, Set<String>> foreignByP = new HashMap<>();
        for (OntologyEdge e : existingAfterUpsert) {
            if (!e.isInferred()) continue;
            if (ownerOf(e) != null && !entityId.equals(ownerOf(e))) {
                foreignByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
                continue;
            }
            existingInfByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
        }
        for (Map.Entry<String, Set<String>> en : existingInfByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = new HashSet<>(newByP.getOrDefault(p, Set.of()));

            for (String dst : en.getValue()) {
                if (!keep.contains(dst)) {
                    existingAfterUpsert.stream()
                            .filter(ex -> Objects.equals(ex.getP(), p) && Objects.equals(ex.getDst(), dst) && ex.isInferred())
                            .findFirst().ifPresent(ex -> changes.removed().add(toEdgeRecord(ex, dataDomain)));
                }
            }

            // delete inferred edges not in keep for predicate p
            keep.addAll(foreignByP.getOrDefault(p, Set.of()));
            edgeRepo.deleteInferredBySrcNotIn(realmId, dataDomain, entityId, p, keep);
        }

        // 4) Prune this entity's inverse and symmetric images on other nodes that it no longer justifies
        pruneImagesElsewhere(realmId, dataDomain, entityId, newElsewhere, changes);
        return existingAfterUpsert;
    }

    /**
     * Delete the inverse and symmetric images {@code entityId} owns on other nodes, except those in
     * {@code keep} (keys {@code [src, p, dst]}), recording each removal.
     */
    private void pruneImagesElsewhere(String realmId, DataDomain dataDomain, String entityId,
                                      Set<List<String>> keep, EdgeChanges changes) {
        for (OntologyEdge e : edgeRepo.findByDst(realmId, dataDomain, entityId)) {
            if (!e.isInferred() || !entityId.equals(ownerOf(e)) || Objects.equals(e.getSrc(), entityId)) continue;
            if (keep.contains(List.of(e.getSrc(), e.getP(), e.getDst()))) continue;
            changes.removed().add(toEdgeRecord(e, dataDomain));
            edgeRepo.deleteInferredEdge(realmId, dataDomain, e.getSrc(), e.getP(), e.getDst());
        }
    }

    /**
     * Remove a deleted entity from the graph: its own edges and the inverse and symmetric images it
     * owns on other nodes. Returns what was removed, so chain propagation can re-derive the nodes
     * those images sat on.
     */
    public EdgeChanges removeNode(String realmId, DataDomain dataDomain, String entityId) {
        EdgeChanges changes = new EdgeChanges();
        for (OntologyEdge e : edgeRepo.findBySrc(realmId, dataDomain, entityId)) {
            changes.removed().add(toEdgeRecord(e, dataDomain));
        }
        edgeRepo.deleteBySrc(realmId, dataDomain, entityId, false);
        pruneImagesElsewhere(realmId, dataDomain, entityId, Set.of(), changes);
        return changes;
    }

    /** Keys {@code [src, p, dst]} of the entity's asserted and computed edges, stored or being written. */
    private static Set<List<String>> assertedKeys(Collection<OntologyEdge> existing, List<Reasoner.Edge> written) {
        Set<List<String>> keys = new HashSet<>();
        for (OntologyEdge e : existing) {
            if (!e.isInferred()) keys.add(List.of(e.getSrc(), e.getP(), e.getDst()));
        }
        if (written != null) {
            for (Reasoner.Edge e : written) {
                if (!e.inferred()) keys.add(List.of(e.srcId(), e.p(), e.dstId()));
            }
        }
        return keys;
    }

    /**
     * The node that owns an inferred edge: for an inverse or symmetric edge, the source of the edge it
     * mirrors (which may be another node); {@code null} for every other inferred edge, which belongs
     * to its own source.
     */
    static String ownerOf(OntologyEdge e) {
        Map<String, Object> prov = e.getProv();
        if (prov == null) return null;
        Object rule = prov.get("rule");
        if (!"inverse".equals(rule) && !"symmetric".equals(rule)) return null;
        Object inputs = prov.get("inputs");
        if (!(inputs instanceof Map<?, ?> m) || !(m.get("inputs") instanceof List<?> l) || l.isEmpty()) return null;
        return l.get(0) instanceof Map<?, ?> in && in.get("src") != null ? String.valueOf(in.get("src")) : null;
    }

    /**
     * Inverse and symmetric edges at {@code entityId} that another node's edge produced. They are part
     * of the entity's snapshot (a chain may start with one) but the entity neither re-derives nor
     * prunes them.
     */
    private static List<Reasoner.Edge> ownedElsewhere(Collection<OntologyEdge> existing, String entityId,
                                                      List<Reasoner.Edge> asserted) {
        Set<List<String>> assertedKeys = new HashSet<>();
        if (asserted != null) {
            for (Reasoner.Edge a : asserted) assertedKeys.add(List.of(a.srcId(), a.p(), a.dstId()));
        }
        List<Reasoner.Edge> out = new ArrayList<>();
        for (OntologyEdge e : existing) {
            String owner = e.isInferred() ? ownerOf(e) : null;
            if (owner == null || owner.equals(entityId)) continue;
            // The entity now asserts this edge itself, so it is no longer another node's
            if (assertedKeys.contains(List.of(e.getSrc(), e.getP(), e.getDst()))) continue;
            out.add(new Reasoner.Edge(e.getSrc(), e.getSrcType(), e.getP(), e.getDst(), e.getDstType(), true,
                    Optional.of(new Reasoner.Provenance(String.valueOf(e.getProv().get("rule")), Map.of()))));
        }
        return out;
    }

    /**
     * Drop the reasoner's inverse and symmetric images of {@code foreign} edges: those images are the
     * owning node's own edges, which this entity must not rewrite as inferred.
     */
    private static Reasoner.InferenceResult withoutImagesOf(Reasoner.InferenceResult out, List<Reasoner.Edge> foreign) {
        if (foreign.isEmpty()) return out;
        Set<List<String>> mirrored = new HashSet<>();
        for (Reasoner.Edge f : foreign) {
            mirrored.add(List.of(f.srcId(), f.p(), f.dstId()));
        }
        List<Reasoner.Edge> kept = new ArrayList<>();
        for (Reasoner.Edge e : out.addEdges()) {
            String rule = e.prov().map(Reasoner.Provenance::rule).orElse(null);
            boolean image = ("inverse".equals(rule) || "symmetric".equals(rule)) && e.prov()
                    .map(pr -> pr.inputs().get("inputs"))
                    .filter(l -> l instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?>)
                    .map(l -> (Map<?, ?>) ((List<?>) l).get(0))
                    .map(in -> mirrored.contains(List.of(String.valueOf(in.get("src")), String.valueOf(in.get("p")),
                            String.valueOf(in.get("dst")))))
                    .orElse(false);
            if (!image) kept.add(e);
        }
        return new Reasoner.InferenceResult(out.addTypes(), out.addLabels(), kept, out.removeEdges());
    }

    /**
     * Re-derive the inferred edges of an entity from its stored base edges (explicit and computed),
     * without touching those base edges. Chain propagation calls this for a chain head whose path
     * changed further along the chain (P2): the head entity itself was not written, so its own
     * explicit edges come from the store rather than from the entity.
     */
    public EdgeChanges rederive(String realmId, DataDomain dataDomain, String entityId, String entityType) {
        if (dataDomain == null) {
            throw new IllegalArgumentException("DataDomain must be provided for materialization");
        }
        EdgeChanges changes = new EdgeChanges();
        OntologyRegistry registry = registryProvider.getRegistryForTenant(dataDomain);
        List<OntologyEdge> existingAll = edgeRepo.findBySrc(realmId, dataDomain, entityId);
        List<Reasoner.Edge> base = new ArrayList<>();
        for (OntologyEdge e : existingAll) {
            if (e.isInferred()) continue;
            base.add(new Reasoner.Edge(e.getSrc(), e.getSrcType(), e.getP(), e.getDst(), e.getDstType(), false,
                    Optional.empty()));
        }
        List<Reasoner.Edge> foreign = ownedElsewhere(existingAll, entityId, base);
        base.addAll(foreign);
        List<Reasoner.Edge> extendedEdges = chainSnapshotEdges(realmId, dataDomain, entityId, base, registry);
        var out = withoutImagesOf(
                reasoner.infer(new Reasoner.EntitySnapshot(dataDomain.getTenantId(), entityId, entityType, extendedEdges), registry),
                foreign);
        upsertAndPruneInferred(realmId, dataDomain, entityId, out, existingAll, assertedKeys(existingAll, base), changes);
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
        rec.setProps(e.getProps());
        rec.setValidFrom(e.getValidFrom());
        rec.setValidTo(e.getValidTo());
        rec.setSecurityLabel(e.getSecurityLabel());
        rec.setCompartments(e.getCompartments());
        rec.setConfidence(e.getConfidence());
        rec.setAssertionMethod(e.getAssertionMethod());
        rec.setAllowedPurposes(e.getAllowedPurposes());
        rec.setConsentId(e.getConsentId());
        rec.setAttestation(e.getAttestation());
        rec.setMutuallyExclusiveWith(e.getMutuallyExclusiveWith());
        rec.setTs(e.getTs() != null ? e.getTs() : new Date());
        return rec;
    }

    private boolean isModified(OntologyEdge existing, OntologyEdge updated) {
        if (existing == null || updated == null) return existing != updated;
        if (existing.isInferred() != updated.isInferred()) return true;
        if (!Objects.equals(existing.isDerived(), updated.isDerived())) return true;
        if (!Objects.equals(existing.getProv(), updated.getProv())) return true;
        if (!Objects.equals(existing.getSupport(), updated.getSupport())) return true;
        if (!Objects.equals(existing.getProps(), updated.getProps())) return true;
        if (!Objects.equals(existing.getValidFrom(), updated.getValidFrom())) return true;
        if (!Objects.equals(existing.getValidTo(), updated.getValidTo())) return true;
        if (!Objects.equals(existing.getSecurityLabel(), updated.getSecurityLabel())) return true;
        if (!Objects.equals(existing.getCompartments(), updated.getCompartments())) return true;
        if (!Objects.equals(existing.getConfidence(), updated.getConfidence())) return true;
        if (!Objects.equals(existing.getAssertionMethod(), updated.getAssertionMethod())) return true;
        if (!Objects.equals(existing.getAllowedPurposes(), updated.getAllowedPurposes())) return true;
        if (!Objects.equals(existing.getConsentId(), updated.getConsentId())) return true;
        if (!Objects.equals(existing.getAttestation(), updated.getAttestation())) return true;
        if (!Objects.equals(existing.getMutuallyExclusiveWith(), updated.getMutuallyExclusiveWith())) return true;
        return false;
    }

    private boolean isModifiedRecord(OntologyEdge existing, EdgeRecord updated) {
        if (existing == null || updated == null) return existing != null || updated != null;
        if (existing.isInferred() != updated.isInferred()) return true;
        if (!Objects.equals(existing.isDerived(), updated.isDerived())) return true;
        if (!Objects.equals(existing.getProv(), updated.getProv())) return true;
        if (!Objects.equals(existing.getProps(), updated.getProps())) return true;
        if (!Objects.equals(existing.getValidFrom(), updated.getValidFrom())) return true;
        if (!Objects.equals(existing.getValidTo(), updated.getValidTo())) return true;
        if (!Objects.equals(existing.getSecurityLabel(), updated.getSecurityLabel())) return true;
        if (!Objects.equals(existing.getCompartments(), updated.getCompartments())) return true;
        if (!Objects.equals(existing.getConfidence(), updated.getConfidence())) return true;
        if (!Objects.equals(existing.getAssertionMethod(), updated.getAssertionMethod())) return true;
        if (!Objects.equals(existing.getAllowedPurposes(), updated.getAllowedPurposes())) return true;
        if (!Objects.equals(existing.getConsentId(), updated.getConsentId())) return true;
        if (!Objects.equals(existing.getAttestation(), updated.getAttestation())) return true;
        if (!Objects.equals(existing.getMutuallyExclusiveWith(), updated.getMutuallyExclusiveWith())) return true;
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
     * <p>An entity whose explicit edges name a stable derived relation (relation contract, P1) is left
     * out entirely (nothing written or pruned for it) and logged; the rest of the batch is
     * materialized. Use {@link #applyBulk(List, Consumer)} to collect those entities.
     *
     * @param entities list of entity edge contexts to process
     * @return combined EdgeChanges for all entities
     */
    public EdgeChanges applyBulk(List<EntityEdgeContext> entities) {
        return applyBulk(entities, r -> io.quarkus.logging.Log.warnf(
                "OntologyMaterializer: skipped %s %s: %s", r.entityType(), r.entityId(), r.message()));
    }

    /**
     * {@link #applyBulk(List)} reporting each entity left out because its explicit edges were refused.
     */
    public EdgeChanges applyBulk(List<EntityEdgeContext> input, java.util.function.Consumer<RejectedEntity> onRejected) {
        if (input == null || input.isEmpty()) {
            return EdgeChanges.empty();
        }
        // Check every entity before anything is written, and drop only the offending ones
        List<EntityEdgeContext> entities = new ArrayList<>(input.size());
        for (EntityEdgeContext ctx : input) {
            if (ctx.dataDomain() != null) {
                try {
                    StableRelationGuard.requireExplicitEdgesAllowed(
                            registryProvider.getRegistryForTenant(ctx.dataDomain()), ctx.explicitEdges());
                } catch (StableRelationGuard.StableDerivedEdgeException rejected) {
                    if (onRejected != null) {
                        onRejected.accept(new RejectedEntity(ctx.entityId(), ctx.entityType(), rejected.getMessage()));
                    }
                    continue;
                }
            }
            entities.add(ctx);
        }
        if (entities.isEmpty()) {
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
        Map<String, Set<List<String>>> entityImagesElsewhere = new HashMap<>();

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

            // Inverse and symmetric edges here that other nodes own join the snapshot (a chain may start
            // with one) but are neither re-inverted nor pruned by this entity
            List<Reasoner.Edge> foreign = ownedElsewhere(existingForEntity.values(), entityId, explicitEdges);

            // Build extended edges for chain inference (same logic as single apply)
            List<Reasoner.Edge> extendedEdges = new ArrayList<>(explicitEdges != null ? explicitEdges : List.of());
            extendedEdges.addAll(foreign);
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
                for (var edge : foreign) {
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
            var out = withoutImagesOf(reasoner.infer(snap, registry), foreign);

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
            Set<List<String>> imagesElsewhere = new HashSet<>();
            Set<List<String>> asserted = assertedKeys(existingForEntity.values(), explicitEdges);
            for (var e : out.addEdges()) {
                OntologyEdge existing;
                if (Objects.equals(e.srcId(), entityId)) {
                    inferredByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                    if (asserted.contains(List.of(e.srcId(), e.p(), e.dstId()))) continue; // asserted here: leave it
                    existing = existingForEntity.get(e.srcId() + "|" + e.p() + "|" + e.dstId());
                } else {
                    // An inverse or symmetric image on another node
                    imagesElsewhere.add(List.of(e.srcId(), e.p(), e.dstId()));
                    existing = findEdge(realmId, dataDomain, e.srcId(), e.p(), e.dstId());
                    if (existing != null && !existing.isInferred()) continue; // asserted there: leave it
                }
                Map<String, Object> prov = e.prov().map(p -> Map.<String, Object>of(
                        "rule", p.rule(),
                        "inputs", p.inputs()
                )).orElse(Map.of());

                // Check if edge already exists with same properties
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
            entityImagesElsewhere.put(entityId, imagesElsewhere);
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

            Map<String, Set<String>> foreignByP = new HashMap<>();
            for (OntologyEdge e : existingMap.values()) {
                if (e.isInferred()) {
                    existingInferredPreds.add(e.getP());
                    String owner = ownerOf(e);
                    if (owner != null && !owner.equals(entityId)) {
                        foreignByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
                    }
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

            // Prune inferred edges, keeping the ones other nodes own
            for (String p : existingInferredPreds) {
                Set<String> keep = new HashSet<>(inferredByP.getOrDefault(p, Set.of()));
                keep.addAll(foreignByP.getOrDefault(p, Set.of()));
                edgeRepo.deleteInferredBySrcNotIn(realmId, dataDomain, entityId, p, keep);
            }

            // Prune this entity's images on other nodes that it no longer justifies
            pruneImagesElsewhere(realmId, dataDomain, entityId,
                    entityImagesElsewhere.getOrDefault(entityId, Set.of()), allChanges);
        }

        return allChanges;
    }
}
