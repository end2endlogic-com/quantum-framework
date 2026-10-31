package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.Realm;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import dev.morphia.transactions.MorphiaSession;
import io.smallrye.mutiny.subscription.MultiEmitter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level coverage for the {@code quantum.realm.seed-system-only} guard in {@link AddRealms}.
 *
 * <p>{@code execute()} only touches its injected {@link RealmRepo} (recorded here) plus a
 * {@code session.getDatabase().getName()} log line and {@code emitter.emit(...)}. The session and
 * emitter are supplied as minimal JDK dynamic proxies, so this runs without Quarkus or Mongo.
 */
class AddRealmsSeedSystemOnlyTest {

    @Test
    void defaultCreatesSystemAndTestRealms() throws Exception {
        RecordingRealmRepo realmRepo = new RecordingRealmRepo();
        AddRealms changeSet = newChangeSet(realmRepo, false);

        changeSet.execute(session("system-com"), mongoClient(), emitter());

        List<String> saved = realmRepo.savedRefNames();
        assertTrue(saved.contains("system-com"), "system realm must be created in default mode");
        assertTrue(saved.contains("test-quantum-com"), "test realm must be created in default mode");
        assertEquals(2, saved.size(), "default mode creates exactly the system + test realms");
    }

    @Test
    void seedSystemOnlyCreatesOnlySystemRealm() throws Exception {
        RecordingRealmRepo realmRepo = new RecordingRealmRepo();
        AddRealms changeSet = newChangeSet(realmRepo, true);

        changeSet.execute(session("system-com"), mongoClient(), emitter());

        List<String> saved = realmRepo.savedRefNames();
        assertTrue(saved.contains("system-com"), "system realm MUST still be created under seed-system-only");
        assertFalse(saved.contains("test-quantum-com"), "test realm must NOT be created under seed-system-only");
        assertEquals(1, saved.size(), "seed-system-only creates exactly the system realm");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static AddRealms newChangeSet(RecordingRealmRepo realmRepo, boolean seedSystemOnly) {
        AddRealms changeSet = new AddRealms();
        changeSet.realmRepo = realmRepo;
        changeSet.systemTenantId = "system.com";
        changeSet.systemOrgRefName = "system.com";
        changeSet.defaultRealm = "mycompanyxyz-com";
        changeSet.accountNumber = "0000000000";
        changeSet.systemRealm = "system-com";
        changeSet.testRealm = "test-quantum-com";
        changeSet.testTenantId = "test-quantum.com";
        changeSet.testOrgRefName = "test-quantum.com";
        changeSet.testAccountNumber = "0000000001";
        changeSet.seedSystemOnly = seedSystemOnly;
        return changeSet;
    }

    /** Minimal MorphiaSession proxy; only getDatabase().getName() is exercised by execute(). */
    private static MorphiaSession session(String dbName) {
        MongoDatabase db = (MongoDatabase) Proxy.newProxyInstance(
                AddRealmsSeedSystemOnlyTest.class.getClassLoader(),
                new Class<?>[]{MongoDatabase.class},
                (proxy, method, args) -> "getName".equals(method.getName()) ? dbName : defaultReturn(method));
        return (MorphiaSession) Proxy.newProxyInstance(
                AddRealmsSeedSystemOnlyTest.class.getClassLoader(),
                new Class<?>[]{MorphiaSession.class},
                (proxy, method, args) -> "getDatabase".equals(method.getName()) ? db : defaultReturn(method));
    }

    private static MongoClient mongoClient() {
        return (MongoClient) Proxy.newProxyInstance(
                AddRealmsSeedSystemOnlyTest.class.getClassLoader(),
                new Class<?>[]{MongoClient.class},
                (proxy, method, args) -> defaultReturn(method));
    }

    @SuppressWarnings("unchecked")
    private static MultiEmitter<? super String> emitter() {
        return (MultiEmitter<? super String>) Proxy.newProxyInstance(
                AddRealmsSeedSystemOnlyTest.class.getClassLoader(),
                new Class<?>[]{MultiEmitter.class},
                (InvocationHandler) (proxy, method, args) ->
                        "emit".equals(method.getName()) ? proxy : defaultReturn(method));
    }

    private static Object defaultReturn(Method method) {
        Class<?> rt = method.getReturnType();
        if (rt.equals(boolean.class)) return false;
        if (rt.equals(int.class)) return 0;
        if (rt.equals(long.class)) return 0L;
        return null;
    }

    private static final class RecordingRealmRepo extends RealmRepo {
        private final List<String> saved = new ArrayList<>();

        List<String> savedRefNames() {
            return saved;
        }

        @Override
        public Optional<Realm> findByRefName(String refName, boolean ignoreRules, String realmId) {
            // Treat every realm as new so saveOrUpdateRealm takes the save() path.
            return Optional.empty();
        }

        @Override
        public Realm save(MorphiaSession session, Realm value) {
            saved.add(value.getRefName());
            return value;
        }
    }
}
