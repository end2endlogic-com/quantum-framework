
package com.e2eq.ontology.mongo;

import java.util.*;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.DataDomainInfo;
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
   protected EdgeStore edgeStore;

    /**
     * Apply materialization for an entity with full DataDomain scoping.
     *
     * @param dataDomain  full DataDomain context for edge scoping
     * @param entityId    ID of the entity
     * @param entityType  type of the entity
     * @param explicitEdges list of explicit edges from the entity
     */
    public void apply(DataDomain dataDomain, String entityId, String entityType, List<Reasoner.Edge> explicitEdges) {
        if (dataDomain == null) {
            throw new IllegalArgumentException("DataDomain must be provided for materialization");
        }
        // Convert to DataDomainInfo for EdgeStore operations
        DataDomainInfo dataDomainInfo = DataDomainConverter.toInfo(dataDomain);
        String tenantId = dataDomain.getTenantId(); // For logging/reasoner compatibility

        var snap = new Reasoner.EntitySnapshot(tenantId, entityId, entityType, explicitEdges);
        // Resolve registry for the given DataDomain
        OntologyRegistry registry = registryProvider.getRegistryForTenant(dataDomain);
        // [DEBUG_LOG] dump registry props
        try { io.quarkus.logging.Log.infof("[DEBUG_LOG] Registry properties: %s", registry.properties().keySet()); } catch (Exception ignored) {}
        var out = reasoner.infer(snap, registry);

        // 1) Upsert EXPLICIT edges as-is for traversal and cascade logic
        // Separate computed edges (from ComputedEdgeProvider) from regular explicit edges
        Map<String, Set<String>> explicitByP = new HashMap<>();
        Map<String, Set<String>> computedByP = new HashMap<>();
        if (explicitEdges != null) {
            for (var e : explicitEdges) {
                // Check if this is a computed edge (from ComputedEdgeProvider)
                boolean isComputed = e.prov().map(p -> "computed".equals(p.rule())).orElse(false);

                if (isComputed) {
                    // Computed edges are tracked separately and stored as derived
                    computedByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                    Map<String, Object> prov = e.prov()
                        .map(p -> Map.<String, Object>of("rule", p.rule(), "inputs", p.inputs()))
                        .orElse(Map.of());
                    // Use upsertDerived for computed edges so they get derived=true flag
                    edgeStore.upsertDerived(dataDomainInfo, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), List.of(), prov);
                } else {
                    // Regular explicit edges
                    explicitByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                    Map<String, Object> prov = e.prov()
                        .map(p -> Map.<String, Object>of("rule", p.rule(), "inputs", p.inputs()))
                        .orElse(Map.of());
                    edgeStore.upsert(dataDomainInfo, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), false, prov);
                }
            }
        }

        // 2) Collect new INFERRED edges by predicate
        Map<String, Set<String>> newByP = new HashMap<>();
        List<EdgeRecord> upserts = new ArrayList<>();
        for (var e : out.addEdges()) {
            newByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
            Map<String,Object> prov = e.prov().map(p -> Map.<String,Object>of(
                    "rule", p.rule(),
                    "inputs", p.inputs()
            )).orElse(Map.of());
            EdgeRecord rec = new EdgeRecord(dataDomainInfo, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), true, prov, new Date());
            upserts.add(rec);
        }
        // [DEBUG_LOG] summarize inferred edges
        try {
            System.out.println("[DEBUG_LOG] OntologyMaterializer.apply inferred edges: src=" + entityId +
                ", dataDomain=" + dataDomain.getOrgRefName() + "/" + dataDomain.getAccountNum() + "/" + tenantId +
                ", count=" + upserts.size() + ", byP=" + newByP);
        } catch (Exception ignored) {}
        if (!upserts.isEmpty()) {
            edgeStore.upsertMany(upserts);
        }
        try {
            var srcEdges = edgeStore.findBySrc(dataDomainInfo, entityId);
            io.quarkus.logging.Log.infof("[DEBUG_LOG] After upsert, edges from src=%s: %s", entityId, srcEdges.stream().map(e -> e.getP()+"->"+e.getDst()).toList());
        } catch (Exception ignored) {}

        // 3) Prune inferred edges no longer justified
        List<EdgeRecord> existing = edgeStore.findBySrc(dataDomainInfo, entityId);
        Map<String, Set<String>> existingInfByP = new HashMap<>();
        for (EdgeRecord e : existing) {
            if (!e.isInferred()) continue;
            existingInfByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
        }
        for (Map.Entry<String, Set<String>> en : existingInfByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = newByP.getOrDefault(p, Set.of());
            // delete inferred edges not in keep for predicate p
            edgeStore.deleteInferredBySrcNotIn(dataDomainInfo, entityId, p, keep);
        }

        // 4) Prune explicit edges that are no longer present in the entity snapshot
        // Determine all predicates that currently exist for this source
        Set<String> existingExplicitPreds = new HashSet<>();
        for (EdgeRecord e : existing) {
            if (!e.isInferred()) existingExplicitPreds.add(e.getP());
        }
        // For predicates present in snapshot: keep only dsts that remain
        for (Map.Entry<String, Set<String>> en : explicitByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = en.getValue();
            edgeStore.deleteExplicitBySrcNotIn(dataDomainInfo, entityId, p, keep);
            existingExplicitPreds.remove(p);
        }
        // For predicates absent now: remove all explicit edges for those predicates
        for (String pAbsent : existingExplicitPreds) {
            edgeStore.deleteExplicitBySrcNotIn(dataDomainInfo, entityId, pAbsent, Set.of());
        }

        // 5) Prune derived/computed edges that are no longer present in the provider output
        // Determine existing derived predicates
        Map<String, Set<String>> existingDerivedByP = new HashMap<>();
        for (EdgeRecord e : existing) {
            if (e.isDerived() && !e.isInferred()) { // derived but not inferred = computed edges
                existingDerivedByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
            }
        }
        // For predicates present in computed snapshot: keep only dsts that remain
        for (Map.Entry<String, Set<String>> en : computedByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = en.getValue();
            edgeStore.deleteDerivedBySrcNotIn(dataDomainInfo, entityId, p, keep);
            existingDerivedByP.remove(p);
        }
        // For predicates with no computed edges now: remove all derived edges for those predicates
        for (String pAbsent : existingDerivedByP.keySet()) {
            edgeStore.deleteDerivedBySrcNotIn(dataDomainInfo, entityId, pAbsent, Set.of());
        }

        // Final debug: show edges after pruning
        try {
            var srcEdges2 = edgeStore.findBySrc(dataDomainInfo, entityId);
            io.quarkus.logging.Log.infof("[DEBUG_LOG] After pruning, edges from src=%s: %s", entityId, srcEdges2.stream().map(e -> (e.isInferred()?"I":"E")+":"+e.getP()+"->"+e.getDst()).toList());
        } catch (Exception ignored) {}
        // TODO: write back types/labels on entity doc if needed
    }
}
