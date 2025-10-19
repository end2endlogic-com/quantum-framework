
package com.e2eq.ontology.mongo;

import java.util.*;
import org.bson.Document;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;

public class EdgeDao {
    private final MongoCollection<Document> edges;
    public EdgeDao(MongoCollection<Document> edges) { this.edges = edges; }

    public void ensureIndexes() {
        // (tenantId, p, dst)
        edges.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId"), Indexes.ascending("p"), Indexes.ascending("dst")),
                new IndexOptions().name("tenant_p_dst"));
        // (tenantId, src, p)
        edges.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId"), Indexes.ascending("src"), Indexes.ascending("p")),
                new IndexOptions().name("tenant_src_p"));
    }

    public void upsert(String tenantId, String src, String p, String dst, boolean inferred, Map<String,Object> prov){
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

    public void upsertMany(Collection<Document> docs) {
        for (Document d : docs) {
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

    public void deleteBySrc(String tenantId, String src, boolean inferredOnly) {
        if (inferredOnly) {
            edges.deleteMany(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("src", src), Filters.eq("inferred", true)));
        } else {
            edges.deleteMany(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("src", src)));
        }
    }

    public void deleteBySrcAndPredicate(String tenantId, String src, String p) {
        edges.deleteMany(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("src", src), Filters.eq("p", p)));
    }

    public void deleteInferredBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep) {
        edges.deleteMany(Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("src", src),
                Filters.eq("p", p),
                Filters.eq("inferred", true),
                Filters.nin("dst", dstKeep)));
    }

    public Set<String> srcIdsByDst(String tenantId, String p, String dst){
        Set<String> ids = new HashSet<>();
        for (Document d : edges.find(Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("p", p),
                Filters.eq("dst", dst)))) {
            ids.add(d.getString("src"));
        }
        return ids;
    }

    public Set<String> srcIdsByDstIn(String tenantId, String p, Collection<String> dstIds){
        Set<String> ids = new HashSet<>();
        for (Document d : edges.find(Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("p", p),
                Filters.in("dst", dstIds)))) {
            ids.add(d.getString("src"));
        }
        return ids;
    }

    public Map<String, Set<String>> srcIdsByDstGrouped(String tenantId, String p, Collection<String> dstIds){
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

    public List<Document> findBySrc(String tenantId, String src){
        return edges.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("src", src)))
            .into(new ArrayList<>());
    }
}
