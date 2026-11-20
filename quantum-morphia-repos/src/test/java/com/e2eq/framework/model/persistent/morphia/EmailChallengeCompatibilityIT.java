package com.e2eq.framework.model.persistent.morphia;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.e2eq.framework.model.auth.EmailChallenge;
import com.e2eq.framework.model.messaging.MessageChannel;
import com.e2eq.framework.model.security.EmailChallengeRecord;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.morphia.Morphia;
import dev.morphia.MorphiaDatastore;
import dev.morphia.config.ManualMorphiaConfig;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Stored challenge compatibility on a real replica set: a document written before channels
 * existed reads back through the same codec path {@code EmailChallengeRepo} uses, and new
 * documents keep the {@code email} field old readers and {@code revokeOtherPending} match on.
 */
class EmailChallengeCompatibilityIT {

    @Test
    void legacyAndChannelDocumentsRoundTrip() {
        String databaseName = "test-quantum-email-challenge-" + UUID.randomUUID().toString().replace("-", "");
        try (MongoClient client = MongoClients.create(
                System.getProperty("quantum.test.mongo-uri",
                        System.getenv().getOrDefault("MONGODB_CONNECTION_STRING", "mongodb://127.0.0.1:27017/?replicaSet=rs0")))) {
            MorphiaDatastore datastore = (MorphiaDatastore) Morphia.createDatastore(
                    client, ManualMorphiaConfig.configure().database(databaseName));
            datastore.getMapper().mapPackage("com.e2eq.framework.model.persistent.base");
            datastore.getMapper().mapPackage("com.e2eq.framework.model.security");
            try {
                var collection = datastore.getCollection(EmailChallengeRecord.class);
                Instant expires = Instant.parse("2026-11-21T00:00:00Z");

                // Exactly the fields a pre-channel binary wrote.
                datastore.getDatabase().getCollection(collection.getNamespace().getCollectionName()).insertOne(new Document()
                        .append("refName", "legacy-1")
                        .append("challengeId", "legacy-1")
                        .append("email", "user@example.test")
                        .append("applicationId", "app")
                        .append("purpose", "PORTAL_SIGN_IN")
                        .append("secretDigest", "digest")
                        .append("expiresAt", Date.from(expires))
                        .append("attempts", 1)
                        .append("maxAttempts", 5)
                        .append("state", "PENDING")
                        .append("revision", 2L));

                EmailChallenge legacy = collection.find(eq("challengeId", "legacy-1")).first().snapshot();
                assertEquals(MessageChannel.EMAIL, legacy.channel());
                assertEquals("user@example.test", legacy.email());
                assertEquals("user@example.test", legacy.recipient());
                assertNull(legacy.redirect());
                assertEquals(2L, legacy.revision());
                assertEquals(expires, legacy.expiresAt());

                var sms = new EmailChallenge("sms-1", null, "app", EmailChallenge.Purpose.PORTAL_SIGN_IN, "digest",
                        null, expires, 0, 5, EmailChallenge.State.PENDING, 0, MessageChannel.SMS, "+15550100", "/orders/42");
                collection.insertOne(EmailChallengeRecord.from(sms));
                assertEquals(sms, collection.find(eq("challengeId", "sms-1")).first().snapshot());

                var email = new EmailChallenge("email-1", "user@example.test", "app", EmailChallenge.Purpose.PORTAL_SIGN_IN,
                        "digest", null, expires, 0, 5, EmailChallenge.State.PENDING, 0);
                collection.insertOne(EmailChallengeRecord.from(email));
                Document raw = datastore.getDatabase().getCollection(collection.getNamespace().getCollectionName())
                        .find(eq("challengeId", "email-1")).first();
                assertEquals("user@example.test", raw.getString("email"));
                assertEquals("EMAIL", raw.getString("channel"));
                assertEquals(2, datastore.getDatabase().getCollection(collection.getNamespace().getCollectionName())
                        .countDocuments(eq("email", "user@example.test")), "email-channel lookups match legacy and new records");
            } finally {
                client.getDatabase(databaseName).drop();
            }
        }
    }
}
