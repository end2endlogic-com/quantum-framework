
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

        // Collect new inferred edges by predicate
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
                    .append("p", e.p())
                    .append("dst", e.dstId())
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

        // Load current inferred edges for this source and prune per predicate
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
        // TODO: write back types/labels on entity doc if needed
    }

    // Convert raw results from legacy store into typed edges to simplify downstream logic
    private List<OntologyEdge> findEdgesBySrcTyped(String tenantId, String src) {
         return edgeRepo.findBySrc(tenantId, src);
    }
}
