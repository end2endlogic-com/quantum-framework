package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.migration.refname.RefNameMergeParticipant;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.ontology.model.OntologyEdge;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps materialized ontology edges consistent when a refName migration merges a record into its canonical twin:
 * every edge whose {@code src} or {@code dst} is the removed record's id is re-pointed to the twin, and an edge the twin
 * already has is removed instead (edges are unique per data domain, src, predicate and dst). Without this, a merged
 * group's members would have no {@code hasMember} edge from the canonical group, and the removed group's edges would
 * keep matching edge-based rules.
 */
@ApplicationScoped
public class OntologyEdgeRefNameMergeParticipant implements RefNameMergeParticipant {

   @Inject
   MorphiaDataStoreWrapper morphiaDataStoreWrapper;

   @Override
   public long references(MongoDatabase database, String id) {
      return edges(database).countDocuments(touching(id));
   }

   @Override
   public void merge(ClientSession session, MongoDatabase database, String id, String targetId) {
      MongoCollection<Document> edges = edges(database);
      for (Document edge : edges.find(session, touching(id)).into(new ArrayList<>())) {
         String src = id.equals(edge.getString("src")) ? targetId : edge.getString("src");
         String dst = id.equals(edge.getString("dst")) ? targetId : edge.getString("dst");
         String predicate = edge.getString("p");
         Document domain = edge.get("dataDomain") instanceof Document value ? value : new Document();
         List<Bson> twin = new ArrayList<>(List.of(Filters.eq("src", src), Filters.eq("p", predicate),
               Filters.eq("dst", dst), Filters.ne("_id", edge.get("_id"))));
         for (String field : List.of("orgRefName", "accountNum", "tenantId", "dataSegment")) {
            twin.add(Filters.eq("dataDomain." + field, domain.get(field)));
         }
         if (edges.countDocuments(session, Filters.and(twin)) > 0) {
            edges.deleteOne(session, Filters.eq("_id", edge.get("_id")));
         } else {
            edges.updateOne(session, Filters.eq("_id", edge.get("_id")), Updates.combine(
                  Updates.set("src", src), Updates.set("dst", dst),
                  Updates.set("refName", src + "|" + predicate + "|" + dst),
                  Updates.set("displayName", src + "|" + predicate + "|" + dst)));
         }
      }
   }

   private MongoCollection<Document> edges(MongoDatabase database) {
      return database.getCollection(morphiaDataStoreWrapper.collectionNameOf(OntologyEdge.class));
   }

   private static Bson touching(String id) {
      return Filters.or(Filters.eq("src", id), Filters.eq("dst", id));
   }
}
