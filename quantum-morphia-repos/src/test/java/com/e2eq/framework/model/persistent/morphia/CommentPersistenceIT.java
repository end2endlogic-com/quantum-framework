package com.e2eq.framework.model.persistent.morphia;

import static dev.morphia.query.filters.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.e2eq.framework.model.persistent.base.ActorReference;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.ExternalEntityReference;
import com.e2eq.framework.model.persistent.collaboration.Comment;
import com.e2eq.framework.model.persistent.collaboration.CommentChain;
import com.e2eq.framework.model.persistent.collaboration.CommentSubjectReference;
import com.e2eq.framework.model.persistent.collaboration.MediaReference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.morphia.Morphia;
import dev.morphia.MorphiaDatastore;
import dev.morphia.config.ManualMorphiaConfig;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Sort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class CommentPersistenceIT {

    @Test
    void persistsCommentHierarchyAndMediaIndexesOnReplicaSet() {
        String databaseName = "test-quantum-comments-"
                + UUID.randomUUID().toString().replace("-", "");
        try (MongoClient client = MongoClients.create(
                "mongodb://127.0.0.1:27017/?replicaSet=rs0")) {
            MorphiaDatastore datastore = (MorphiaDatastore) Morphia.createDatastore(
                    client,
                    ManualMorphiaConfig.configure().database(databaseName));
            datastore.getMapper().mapPackage(
                    "com.e2eq.framework.model.persistent.base");
            datastore.getMapper().mapPackage(
                    "com.e2eq.framework.model.persistent.collaboration");
            datastore.ensureIndexes(CommentChain.class);
            datastore.ensureIndexes(Comment.class);
            datastore.ensureIndexes(MediaReference.class);

            ActorReference actor = ActorReference.builder()
                    .actorId("alice@example.com")
                    .displayName("Alice")
                    .build();
            DataDomain domain = new DataDomain(
                    "org-alpha",
                    "account-alpha",
                    "tenant-alpha",
                    0,
                    "alice@example.com");
            Instant createdAt = Instant.now();
            CommentChain chain = CommentChain.builder()
                    .refName("chain-defect-42")
                    .displayName("Defect 42")
                    .dataDomain(domain)
                    .subject(CommentSubjectReference.builder()
                            .externalEntity(ExternalEntityReference.builder()
                                    .sourceSystem("issue-tracker")
                                    .entityType("Defect")
                                    .externalId("defect-42")
                                    .build())
                            .build())
                    .createdBy(actor)
                    .createdAt(createdAt)
                    .build();
            datastore.save(chain);
            assertNotNull(chain.getId());

            Comment root = Comment.builder()
                    .refName("comment-root-42")
                    .displayName("Root")
                    .dataDomain(domain)
                    .chainId(chain.getId())
                    .depth(0)
                    .body("Root comment")
                    .author(actor)
                    .createdAt(createdAt)
                    .build();
            datastore.save(root);
            Comment reply = Comment.builder()
                    .refName("comment-reply-42")
                    .displayName("Reply")
                    .dataDomain(domain)
                    .chainId(chain.getId())
                    .parentCommentId(root.getId())
                    .depth(1)
                    .body("Reply comment")
                    .author(actor)
                    .createdAt(createdAt.plusMillis(1))
                    .build();
            datastore.save(reply);

            List<Comment> comments = datastore.find(Comment.class)
                    .filter(eq("chainId", chain.getId()))
                    .iterator(new FindOptions().sort(
                            Sort.ascending("createdAt"),
                            Sort.ascending("_id")))
                    .toList();
            assertEquals(List.of("Root comment", "Reply comment"),
                    comments.stream().map(Comment::getBody).toList());
            assertEquals(root.getId(), comments.get(1).getParentCommentId());

            MediaReference media = MediaReference.builder()
                    .refName("media-screenshot-42")
                    .displayName("Screenshot")
                    .dataDomain(domain)
                    .storageProvider("test")
                    .storageContainer("comment-media")
                    .objectKey("defect-42/screenshot.png")
                    .displayFileName("screenshot.png")
                    .contentType("image/png")
                    .contentLength(1234)
                    .createdBy(actor)
                    .createdAt(createdAt)
                    .build();
            datastore.save(media);
            assertNotNull(media.getId());

            assertTrue(indexNames(client, databaseName, "comments")
                    .contains("idx_comment_chain_parent_created"));
            assertTrue(indexNames(client, databaseName, "media_references")
                    .contains("uidx_media_storage_object_tenant"));
            assertTrue(indexNames(client, databaseName, "comment_chains")
                    .contains("uidx_comment_chain_external_subject"));
        } finally {
            try (MongoClient cleanup = MongoClients.create("mongodb://127.0.0.1:27017")) {
                cleanup.getDatabase(databaseName).drop();
            }
        }
    }

    private static List<String> indexNames(
            MongoClient client,
            String databaseName,
            String collectionName) {
        return client.getDatabase(databaseName)
                .getCollection(collectionName)
                .listIndexes(Document.class)
                .into(new java.util.ArrayList<>())
                .stream()
                .map(index -> index.getString("name"))
                .toList();
    }
}
