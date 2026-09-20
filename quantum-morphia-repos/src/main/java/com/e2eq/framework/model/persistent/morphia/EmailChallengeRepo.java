package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.auth.EmailChallenge;
import com.e2eq.framework.model.auth.EmailChallengeRepository;
import com.e2eq.framework.model.security.EmailChallengeRecord;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static com.mongodb.client.model.Indexes.ascending;

/** Single-issuer system-realm repository with compare-and-set consumption. */
@ApplicationScoped
public class EmailChallengeRepo extends MorphiaRepo<EmailChallengeRecord> implements EmailChallengeRepository {
    private MongoCollection<EmailChallengeRecord> collection() {
        return morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm())
                .getCollection(EmailChallengeRecord.class);
    }
    public void insertChallenge(EmailChallenge challenge) {
        var collection = collection();
        collection.createIndex(ascending("challengeId"), new IndexOptions().unique(true));
        // Expiry is enforced by the verifier, independently of asynchronous TTL cleanup.
        collection.createIndex(ascending("expiresAt"), new IndexOptions().expireAfter(86400L, java.util.concurrent.TimeUnit.SECONDS));
        collection.insertOne(EmailChallengeRecord.from(challenge));
    }

    /** Atomic fixed-window send budget. key must be an authority-keyed digest, never an address. */
    public boolean reserveSendBudget(String key, java.time.Instant now, int seconds, int limit) {
        if (seconds < 1 || limit < 1) throw new IllegalArgumentException("Invalid send budget");
        var buckets = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm())
                .getDatabase().getCollection("emailChallengeSendBudget");
        buckets.createIndex(ascending("expiresAt"), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
        long window = now.getEpochSecond() / seconds;
        String bucket = key + ":" + seconds + ":" + window;
        try {
            buckets.updateOne(eq("_id", bucket), combine(setOnInsert("count", 0),
                    setOnInsert("expiresAt", java.util.Date.from(java.time.Instant.ofEpochSecond((window + 2) * seconds)))),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
        } catch (com.mongodb.MongoWriteException e) {
            // A simultaneous first insertion of this same unique bucket won. It is now
            // safe to use the same atomic counter; all other storage failures propagate.
            if (e.getError().getCode() != 11000) throw e;
        }
        return buckets.updateOne(and(eq("_id", bucket), lt("count", limit)), inc("count", 1))
                .getModifiedCount() == 1;
    }

    public void revokeOtherPending(EmailChallenge current) {
        collection().updateMany(and(eq("email", current.email()), eq("applicationId", current.applicationId()),
                        eq("purpose", current.purpose().name()), ne("challengeId", current.id()),
                        in("state", EmailChallenge.State.PENDING.name(), EmailChallenge.State.PENDING_DELIVERY.name())),
                combine(set("state", EmailChallenge.State.REVOKED.name()), inc("revision", 1)));
    }

    public boolean reserveCooldown(String key, java.time.Instant now, int seconds) {
        var buckets = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm())
                .getDatabase().getCollection("emailChallengeSendCooldown");
        buckets.createIndex(ascending("expiresAt"), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
        try {
            buckets.updateOne(eq("_id", key), setOnInsert("nextSendAt", new java.util.Date(0)),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getError().getCode() != 11000) throw e;
        }
        return buckets.updateOne(and(eq("_id", key), lte("nextSendAt", java.util.Date.from(now))),
                combine(set("nextSendAt", java.util.Date.from(now.plusSeconds(seconds))),
                        set("expiresAt", java.util.Date.from(now.plusSeconds(86400)))))
                .getModifiedCount() == 1;
    }
    @Override public Optional<EmailChallenge> find(String id) {
        return Optional.ofNullable(collection().find(eq("challengeId", id)).first())
                .map(EmailChallengeRecord::snapshot);
    }
    @Override public boolean compareAndSet(EmailChallenge expected, EmailChallenge replacement) {
        if (!expected.id().equals(replacement.id()) || replacement.revision() != expected.revision() + 1) {
            throw new IllegalArgumentException("Invalid challenge transition");
        }
        // Update only the mutable lifecycle fields; preserve persisted domain/audit metadata.
        return collection().updateOne(and(eq("challengeId", expected.id()), eq("revision", expected.revision()),
                        eq("state", expected.state().name())),
                combine(set("state", replacement.state().name()), set("attempts", replacement.attempts()),
                        set("revision", replacement.revision()))).getModifiedCount() == 1;
    }
}
