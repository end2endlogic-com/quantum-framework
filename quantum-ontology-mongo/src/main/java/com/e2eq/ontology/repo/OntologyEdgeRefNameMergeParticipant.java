package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.migration.refname.RefNameMergeParticipant;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.ontology.model.OntologyEdge;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Keeps materialized ontology edges consistent when a refName migration merges a record into its canonical twin:
 * every edge whose {@code src} or {@code dst} is the removed record's id is re-pointed to the twin, and an edge the twin
 * already has is removed instead (edges are unique per data domain, src, predicate and dst). Without this, a merged
 * group's members would have no {@code hasMember} edge from the canonical group, and the removed group's edges would
 * keep matching edge-based rules.
 *
 * <p>Edges that name the record by refName (an {@code EntityReference} without an id) are not rewritten; they are
 * counted so the migration reports such a record as a conflict instead of renaming or merging it.</p>
 */
@ApplicationScoped
public class OntologyEdgeRefNameMergeParticipant implements RefNameMergeParticipant {

   static final String MIGRATION_IDENTITY = "quantum-refname-migration";

   /** The collation of the refName index every entity inherits. */
   private static final Collation REF_NAME_COLLATION = Collation.builder()
         .locale("en").collationStrength(CollationStrength.SECONDARY).build();

   @Inject
   MorphiaDataStoreWrapper morphiaDataStoreWrapper;

   @Override
   public long references(MongoDatabase database, String id) {
      return edges(database).countDocuments(touching(id));
   }

   @Override
   public long referencesByName(MongoDatabase database, String entityType, String refName) {
      return edges(database).countDocuments(Filters.or(
            Filters.and(Filters.eq("src", refName), Filters.eq("srcType", entityType)),
            Filters.and(Filters.eq("dst", refName), Filters.eq("dstType", entityType))));
   }

   @Override
   public void merge(ClientSession session, MongoDatabase database, String id, String targetId) {
      MongoCollection<Document> edges = edges(database);
      for (Document edge : edges.find(session, touching(id)).into(new ArrayList<>())) {
         String src = id.equals(edge.getString("src")) ? targetId : edge.getString("src");
         String dst = id.equals(edge.getString("dst")) ? targetId : edge.getString("dst");
         String predicate = edge.getString("p");
         String refName = src + "|" + predicate + "|" + dst;
         Document domain = edge.get("dataDomain") instanceof Document value ? value : new Document();

         // uniq_domain_src_p_dst: the twin already has this edge, so this one is a duplicate.
         List<Bson> sameEdge = new ArrayList<>(List.of(Filters.eq("src", src), Filters.eq("p", predicate),
               Filters.eq("dst", dst), Filters.ne("_id", edge.get("_id"))));
         for (String field : List.of("orgRefName", "accountNum", "tenantId", "dataSegment")) {
            sameEdge.add(Filters.eq("dataDomain." + field, domain.get(field)));
         }
         Document twin = edges.find(session, Filters.and(sameEdge)).first();
         if (twin != null) {
            // Write the edge that stays, so a concurrent change to it conflicts with this transaction instead of
            // leaving neither edge once the duplicate is gone.
            long touched = edges.updateOne(session, Filters.eq("_id", twin.get("_id")), Updates.combine(
                  Updates.set("auditInfo.lastUpdateTs", new Date()),
                  Updates.set("auditInfo.lastUpdateIdentity", MIGRATION_IDENTITY))).getMatchedCount();
            if (touched != 1) {
               throw new IllegalStateException("refName migration: edge " + twin.get("_id") + " changed concurrently");
            }
            edges.deleteOne(session, Filters.eq("_id", edge.get("_id")));
            continue;
         }

         // idx_refIdUniqueInDomain (inherited): refName within org, tenant and owner, without case.
         List<Bson> sameName = new ArrayList<>(List.of(Filters.eq("refName", refName), Filters.ne("_id", edge.get("_id"))));
         for (String field : List.of("orgRefName", "tenantId", "ownerId")) {
            sameName.add(Filters.eq("dataDomain." + field, domain.get(field)));
         }
         if (edges.find(session, Filters.and(sameName)).collation(REF_NAME_COLLATION).first() != null) {
            throw new IllegalStateException("refName migration: edge " + edge.get("_id") + " cannot become '" + refName
                  + "': another edge in its data domain already holds that refName (different account or segment)");
         }
         edges.updateOne(session, Filters.eq("_id", edge.get("_id")), Updates.combine(
               Updates.set("src", src), Updates.set("dst", dst),
               Updates.set("refName", refName), Updates.set("displayName", refName),
               Updates.set("auditInfo.lastUpdateTs", new Date()),
               Updates.set("auditInfo.lastUpdateIdentity", MIGRATION_IDENTITY)));
      }
   }

   private MongoCollection<Document> edges(MongoDatabase database) {
      return database.getCollection(morphiaDataStoreWrapper.collectionNameOf(OntologyEdge.class));
   }

   private static Bson touching(String id) {
      return Filters.or(Filters.eq("src", id), Filters.eq("dst", id));
   }
}
