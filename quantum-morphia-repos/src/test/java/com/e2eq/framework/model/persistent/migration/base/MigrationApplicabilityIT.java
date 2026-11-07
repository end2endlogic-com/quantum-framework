package com.e2eq.framework.model.persistent.migration.base;

import com.e2eq.framework.model.persistent.morphia.ChangeSetRecordRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoClient;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.config.ManualMorphiaConfig;
import dev.morphia.transactions.MorphiaSession;
import io.smallrye.mutiny.subscription.MultiEmitter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the production runner and real replica-set transactions; catalog/version seams are fixtures. */
class MigrationApplicabilityIT {
    @Entity("migrationProbe") public static class Probe {
        @Id public String id;
        public Probe() {}
        Probe(String id) { this.id = id; }
    }

    @Test void emptyAndNullApplyButExplicitOtherRealmDoesNot() {
        withService(service -> {
            service.catalog = List.of(new Step("null", null, false), new Step("empty", Set.of(), false),
                new Step("other", Set.of("another-realm"), false));
            service.runAllUnRunMigrations(service.realm, emitter());
            assertEquals(Set.of("null", "empty", "log-null", "log-empty"),
                new HashSet<>(service.store.find(Probe.class).iterator().toList().stream().map(p -> p.id).toList()));
            assertTrue(service.versionAdvanced);
        });
    }

    @Test void failedApplicableMigrationRollsBackAndDoesNotAdvanceVersion() {
        withService(service -> {
            service.catalog = List.of(new Step("fails", Set.of(), true));
            assertThrows(RuntimeException.class, () -> service.runAllUnRunMigrations(service.realm, emitter()));
            assertEquals(0, service.store.find(Probe.class).count());
            assertFalse(service.versionAdvanced);
        });
    }

    @SuppressWarnings("unchecked") private static MultiEmitter<String> emitter() { return mock(MultiEmitter.class); }

    private static void withService(java.util.function.Consumer<TestService> test) {
        String database = "test-migration-" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create(System.getProperty("quantum.test.mongo-uri", "mongodb://127.0.0.1:27017/?replicaSet=rs0"))) {
            assertNotNull(client.getDatabase("admin").runCommand(new org.bson.Document("hello",1)).get("setName"));
            var store = (dev.morphia.MorphiaDatastore) Morphia.createDatastore(client, ManualMorphiaConfig.configure().database(database));
            store.getMapper().map(Probe.class);
            store.getDatabase().createCollection("migrationProbe");
            var service = new TestService();
            service.realm = database; service.store = store; service.mongoClient = client;
            service.coordinationDatabase = database + "-locks"; service.targetDatabaseVersion = "1.0.3";
            service.startupRealmsCsv = Optional.empty();
            service.changesetRecordRepo = mock(ChangeSetRecordRepo.class);
            when(service.changesetRecordRepo.findLatestByChangeSetName(any(Datastore.class), anyString())).thenReturn(Optional.empty());
            service.morphiaDataStoreWrapper = mock(MorphiaDataStoreWrapper.class);
            when(service.morphiaDataStoreWrapper.getDataStore(database)).thenReturn(store);
            try { test.accept(service); }
            finally { store.getDatabase().drop(); client.getDatabase(service.coordinationDatabase).drop(); }
        }
    }

    static class TestService extends MigrationService {
        String realm; Datastore store; List<ChangeSetBean> catalog; boolean versionAdvanced;
        @Override public List<ChangeSetBean> getAllPendingChangeSetBeans(String realm, MultiEmitter<? super String> emitter) { return catalog; }
        @Override public Optional<DatabaseVersion> getCurrentDatabaseVersion(Datastore ds) { return Optional.empty(); }
        @Override public DatabaseVersion saveDatabaseVersion(Datastore ds, String version) {
            versionAdvanced = true; var result = new DatabaseVersion(); result.setCurrentVersionString(version); return result;
        }
        @Override public void updateChangeLog(MorphiaSession session, String realm, ChangeSetBean step, MultiEmitter<? super String> emitter) {
            session.save(new Probe("log-" + step.getName()));
        }
    }

    static class Step extends ChangeSetBase {
        final String name; final Set<String> realms; final boolean fail;
        Step(String name, Set<String> realms, boolean fail) { this.name=name; this.realms=realms; this.fail=fail; }
        @Override public String getId() { return name; }
        @Override public String getName() { return name; }
        @Override public String getAuthor() { return "test"; }
        @Override public String getDescription() { return "transaction fixture"; }
        @Override public String getScope() { return "ALL"; }
        @Override public String getDbFromVersion() { return "1.0.2"; }
        @Override public String getDbToVersion() { return "1.0.3"; }
        @Override public int getDbFromVersionInt() { return 102; }
        @Override public int getDbToVersionInt() { return 103; }
        @Override public int getPriority() { return 1; }
        @Override public Set<String> getApplicableDatabases() { return realms; }
        @Override public void execute(MorphiaSession session, MongoClient client, MultiEmitter<? super String> emitter) {
            session.save(new Probe(name));
            if (fail) throw new IllegalStateException("fixture failure after write");
        }
    }
}
