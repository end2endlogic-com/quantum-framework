
package com.e2eq.ontology.mongo;

import java.util.*;
import org.bson.Document;
import com.e2eq.ontology.core.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OntologyMaterializer {

    private final Reasoner reasoner;
    private final OntologyRegistry registry;
    private final EdgeRelationStore edgeDao;

    public OntologyMaterializer(Reasoner reasoner, OntologyRegistry registry, EdgeRelationStore edgeDao) {
        this.reasoner = reasoner;
        this.registry = registry;
        this.edgeDao = edgeDao;
    }

    public void apply(String tenantId, String entityId, String entityType, List<Reasoner.Edge> explicitEdges){
        var snap = new Reasoner.EntitySnapshot(tenantId, entityId, entityType, explicitEdges);
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
        if (!upserts.isEmpty()) {
            edgeDao.upsertMany(upserts);
        }

        // Load current inferred edges for this source and prune per predicate
        List<?> existing = edgeDao.findBySrc(tenantId, entityId);
        Map<String, Set<String>> existingInfByP = new HashMap<>();
        for (Object o : existing) {
            String p;
            String dst;
            Boolean inferred;
            if (o instanceof org.bson.Document d) {
                inferred = d.getBoolean("inferred");
                p = d.getString("p");
                dst = d.getString("dst");
            } else if (o instanceof com.e2eq.ontology.model.OntologyEdge e) {
                inferred = e.isInferred();
                p = e.getP();
                dst = e.getDst();
            } else {
                continue;
            }
            if (!Boolean.TRUE.equals(inferred)) continue;
            existingInfByP.computeIfAbsent(p, k -> new HashSet<>()).add(dst);
        }
        for (Map.Entry<String, Set<String>> en : existingInfByP.entrySet()) {
            String p = en.getKey();
            Set<String> keep = newByP.getOrDefault(p, Set.of());
            // delete inferred edges not in keep for predicate p
            edgeDao.deleteInferredBySrcNotIn(tenantId, entityId, p, keep);
        }
        // TODO: write back types/labels on entity doc if needed
    }
}
