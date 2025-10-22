
package com.e2eq.ontology.mongo;

import java.util.*;
import org.bson.Document;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class EdgeDao implements EdgeRelationStore {
    private MongoCollection<Document> edges; // kept for backward-compat and tests

    @Inject
    MongoClient mongoClient; // used only for legacy/raw path and tests

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @ConfigProperty(name = "ontology.mongo.collection.edges", defaultValue = "edges")
    String collectionName;

    // New: Morphia-backed repository for typed operations
    @jakarta.inject.Inject
    com.e2eq.ontology.repo.OntologyEdgeRepo edgeRepo;

    public EdgeDao() { }

    @PostConstruct
    void init() {
        // Initialize raw collection for tests that rely on it; Morphia path does not require this
        this.edges = mongoClient.getDatabase(databaseName).getCollection(collectionName);
        ensureIndexes();
    }

    public void ensureIndexes() {
        // (tenantId, p, dst)
        edges.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId"), Indexes.ascending("p"), Indexes.ascending("dst")),
                new IndexOptions().name("tenant_p_dst"));
        // (tenantId, src, p)
        edges.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId"), Indexes.ascending("src"), Indexes.ascending("p")),
                new IndexOptions().name("tenant_src_p"));
    }

    @Override
    public void upsert(String tenantId, String src, String p, String dst, boolean inferred, Map<String,Object> prov){
        // Prefer Morphia-backed typed model
        if (edgeRepo != null) {
            edgeRepo.upsert(tenantId, src, p, dst, inferred, prov);
            return;
        }
        // Legacy/raw fallback
        Document doc = new Document("tenantId", tenantId)
            .append("src", src).append("p", p).append("dst", dst)
            .append("inferred", inferred).append("prov", prov)
            .append("ts", new Date());
        edges.replaceOne(Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("src", src),
                Filters.eq("p", p),
                Filters.eq("dst", dst)),
            doc, new ReplaceOptions().upsert(true));
    }

    @Override
    public void upsertMany(Collection<?> docs) {
        if (edgeRepo != null) {
            edgeRepo.upsertMany(docs);
            return;
        }
        for (Object o : docs) {
            if (!(o instanceof Document d)) continue;
            String tenantId = d.getString("tenantId");
            String src = d.getString("src");
            String p = d.getString("p");
            String dst = d.getString("dst");
            edges.replaceOne(Filters.and(
                            Filters.eq("tenantId", tenantId),
                            Filters.eq("src", src),
                            Filters.eq("p", p),
                            Filters.eq("dst", dst)),
                    d, new ReplaceOptions().upsert(true));
        }
    }

    @Override
    public void deleteBySrc(String tenantId, String src, boolean inferredOnly) {
        if (edgeRepo != null) {
            edgeRepo.deleteBySrc(tenantId, src, inferredOnly);
            return;
        }
        if (inferredOnly) {
            edges.deleteMany(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("src", src), Filters.eq("inferred", true)));
        } else {
            edges.deleteMany(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("src", src)));
        }
    }

    @Override
    public void deleteBySrcAndPredicate(String tenantId, String src, String p) {
        if (edgeRepo != null) {
            edgeRepo.deleteBySrcAndPredicate(tenantId, src, p);
            return;
        }
        edges.deleteMany(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("src", src), Filters.eq("p", p)));
    }

    @Override
    public void deleteInferredBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep) {
        if (edgeRepo != null) {
            edgeRepo.deleteInferredBySrcNotIn(tenantId, src, p, dstKeep);
            return;
        }
        edges.deleteMany(Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("src", src),
                Filters.eq("p", p),
                Filters.eq("inferred", true),
                Filters.nin("dst", dstKeep)));
    }

    @Override
    public Set<String> srcIdsByDst(String tenantId, String p, String dst){
        if (edgeRepo != null) {
            return edgeRepo.srcIdsByDst(tenantId, p, dst);
        }
        Set<String> ids = new HashSet<>();
        for (Document d : edges.find(Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("p", p),
                Filters.eq("dst", dst)))) {
            ids.add(d.getString("src"));
        }
        return ids;
    }

    @Override
    public Set<String> srcIdsByDstIn(String tenantId, String p, Collection<String> dstIds){
        if (edgeRepo != null) {
            return edgeRepo.srcIdsByDstIn(tenantId, p, dstIds);
        }
        Set<String> ids = new HashSet<>();
        for (Document d : edges.find(Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("p", p),
                Filters.in("dst", dstIds)))) {
            ids.add(d.getString("src"));
        }
        return ids;
    }

    @Override
    public Map<String, Set<String>> srcIdsByDstGrouped(String tenantId, String p, Collection<String> dstIds){
        if (edgeRepo != null) {
            return edgeRepo.srcIdsByDstGrouped(tenantId, p, dstIds);
        }
        Map<String, Set<String>> map = new HashMap<>();
        for (Document d : edges.find(Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("p", p),
                Filters.in("dst", dstIds))).sort(Sorts.ascending("dst"))) {
            String dst = d.getString("dst");
            map.computeIfAbsent(dst, k -> new HashSet<>()).add(d.getString("src"));
        }
        return map;
    }

    @Override
    public List<Document> findBySrc(String tenantId, String src){
        if (edgeRepo != null) {
            List<com.e2eq.ontology.model.OntologyEdge> list = edgeRepo.findBySrc(tenantId, src);
            List<Document> out = new ArrayList<>(list.size());
            for (com.e2eq.ontology.model.OntologyEdge e : list) {
                String t = (e.getDataDomain() != null) ? e.getDataDomain().getTenantId() : tenantId;
                Document d = new Document("tenantId", t)
                        .append("src", e.getSrc())
                        .append("p", e.getP())
                        .append("dst", e.getDst())
                        .append("inferred", e.isInferred())
                        .append("prov", e.getProv())
                        .append("ts", e.getTs());
                out.add(d);
            }
            return out;
        }
        return edges.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("src", src)))
            .into(new ArrayList<>());
    }
}
