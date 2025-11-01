
package com.e2eq.ontology.mongo;

import java.util.*;

import jakarta.inject.Inject;
import org.bson.Document;
import com.e2eq.ontology.core.*;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OntologyMaterializer {

   @Inject
   protected Reasoner reasoner;

   @Inject
   protected OntologyRegistry registry;

    @Inject
    protected OntologyEdgeRepo edgeRepo;


    public void apply(String tenantId, String entityId, String entityType, List<Reasoner.Edge> explicitEdges){
        var snap = new Reasoner.EntitySnapshot(tenantId, entityId, entityType, explicitEdges);
        // [DEBUG_LOG] dump registry props
        try { io.quarkus.logging.Log.infof("[DEBUG_LOG] Registry properties: %s", registry.properties().keySet()); } catch (Exception ignored) {}
        var out = reasoner.infer(snap, registry);

        // 1) Upsert EXPLICIT edges as-is for traversal and cascade logic
        Map<String, Set<String>> explicitByP = new HashMap<>();
        if (explicitEdges != null) {
            for (var e : explicitEdges) {
                explicitByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
                edgeRepo.upsert(tenantId, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), false, Map.of());
            }
        }

        // 2) Collect new INFERRED edges by predicate
        Map<String, Set<String>> newByP = new HashMap<>();
        List<Document> upserts = new ArrayList<>();
        for (var e : out.addEdges()) {
            newByP.computeIfAbsent(e.p(), k -> new HashSet<>()).add(e.dstId());
            Map<String,Object> prov = e.prov().map(p -> Map.<String,Object>of(
                    "rule", p.rule(),
                    "inputs", p.inputs()
            )).orElse(Map.of());
            Document doc = new Document("tenantId", tenantId)
                    .append("src", e.srcId())
                    .append("srcType", e.srcType())
                    .append("p", e.p())
                    .append("dst", e.dstId())
                    .append("dstType", e.dstType())
                    .append("inferred", true)
                    .append("prov", prov)
                    .append("ts", new Date());
            upserts.add(doc);
        }
        // [DEBUG_LOG] summarize inferred edges
        try {
            System.out.println("[DEBUG_LOG] OntologyMaterializer.apply inferred edges: src=" + entityId + ", tenant=" + tenantId + ", count=" + upserts.size() + ", byP=" + newByP);
        } catch (Exception ignored) {}
        if (!upserts.isEmpty()) {
            // simple data conversion path supported by both repo and legacy store
            edgeRepo.upsertMany(upserts);
        }
        try {
            var srcEdges = edgeRepo.findBySrc(tenantId, entityId);
            io.quarkus.logging.Log.infof("[DEBUG_LOG] After upsert, edges from src=%s: %s", entityId, srcEdges.stream().map(e -> e.getP()+"->"+e.getDst()).toList());
        } catch (Exception ignored) {}

        // 3) Prune inferred edges no longer justified
        List<OntologyEdge> existing = findEdgesBySrcTyped(tenantId, entityId);
        Map<String, Set<String>> existingInfByP = new HashMap<>();
        for (OntologyEdge e : existing) {
            if (!e.isInferred()) continue;
            existingInfByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
        }
        for (Map.Entry<String, Set<String>> en : existingInfByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = newByP.getOrDefault(p, Set.of());
            // delete inferred edges not in keep for predicate p
            edgeRepo.deleteInferredBySrcNotIn(tenantId, entityId, p, keep);
        }

        // 4) Prune explicit edges that are no longer present in the entity snapshot
        // Determine all predicates that currently exist for this source
        Set<String> existingExplicitPreds = new HashSet<>();
        for (OntologyEdge e : existing) {
            if (!e.isInferred()) existingExplicitPreds.add(e.getP());
        }
        // For predicates present in snapshot: keep only dsts that remain
        for (Map.Entry<String, Set<String>> en : explicitByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = en.getValue();
            edgeRepo.deleteExplicitBySrcNotIn(tenantId, entityId, p, keep);
            existingExplicitPreds.remove(p);
        }
        // For predicates absent now: remove all explicit edges for those predicates
        for (String pAbsent : existingExplicitPreds) {
            edgeRepo.deleteExplicitBySrcNotIn(tenantId, entityId, pAbsent, Set.of());
        }
        // Final debug: show edges after pruning
        try {
            var srcEdges2 = edgeRepo.findBySrc(tenantId, entityId);
            io.quarkus.logging.Log.infof("[DEBUG_LOG] After pruning, edges from src=%s: %s", entityId, srcEdges2.stream().map(e -> (e.isInferred()?"I":"E")+":"+e.getP()+"->"+e.getDst()).toList());
        } catch (Exception ignored) {}
        // TODO: write back types/labels on entity doc if needed
    }

    // Convert raw results from legacy store into typed edges to simplify downstream logic
    private List<OntologyEdge> findEdgesBySrcTyped(String tenantId, String src) {
         return edgeRepo.findBySrc(tenantId, src);
    }
}
