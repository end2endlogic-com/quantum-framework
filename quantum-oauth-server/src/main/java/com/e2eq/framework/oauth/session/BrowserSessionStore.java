package com.e2eq.framework.oauth.session;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.util.EnvConfigUtils;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static com.mongodb.client.model.Filters.*;

/** Typed boundary for optional, issuer-owned opaque proofs. Only hashes are stored. */
@ApplicationScoped
public class BrowserSessionStore {
    @Inject MorphiaDataStoreWrapper stores;
    @Inject EnvConfigUtils environment;

    public record Session(String subject, String userId, long passwordRevision, String provider, Instant expiresAt) { }

    private MongoCollection<Document> collection() {
        return stores.getDataStore(environment.getSystemRealm()).getDatabase().getCollection("oauth_browser_proofs");
    }
    public void initialize() {
        collection().createIndex(new Document("expiresAt", 1), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }
    public void saveSession(String hash, String issuer, Session session) {
        collection().insertOne(new Document("_id", hash).append("kind", "SESSION").append("issuer", issuer)
                .append("subject", session.subject()).append("userId", session.userId())
                .append("revision", session.passwordRevision()).append("provider", session.provider())
                .append("expiresAt", Date.from(session.expiresAt())));
    }
    public Optional<Session> session(String hash, String issuer, Instant now) {
        var row = collection().find(and(eq("_id", hash), eq("kind", "SESSION"), eq("issuer", issuer),
                gt("expiresAt", Date.from(now)))).first();
        if (row == null) return Optional.empty();
        return Optional.of(new Session(row.getString("subject"), row.getString("userId"), row.getLong("revision"),
                row.getString("provider"), row.getDate("expiresAt").toInstant()));
    }
    public void revoke(String hash, String issuer) {
        collection().deleteOne(and(eq("_id", hash), eq("issuer", issuer), eq("kind", "SESSION")));
    }
    public void saveLogin(String hash, String issuer, String browserHash, String requestHash, Instant expiresAt) {
        collection().insertOne(new Document("_id", hash).append("kind", "LOGIN").append("issuer", issuer)
                .append("browser", browserHash).append("request", requestHash).append("expiresAt", Date.from(expiresAt)));
    }
    public boolean consumeLogin(String hash, String issuer, String browserHash, String requestHash, Instant now) {
        return collection().findOneAndDelete(and(eq("_id", hash), eq("issuer", issuer), eq("kind", "LOGIN"),
                eq("browser", browserHash), eq("request", requestHash), gt("expiresAt", Date.from(now)))) != null;
    }
}
