package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.RealmEntityMappingPolicy;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.test.ParentModel;
import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.codec.pojo.EntityModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A realm datastore built from the base datastore's entity types maps and indexes the same entities as
 * one built by scanning the configured packages.
 */
@QuarkusTest
public class TestRealmDatastoreMapping extends BaseRepoTest {

    @Inject
    MorphiaDataStoreWrapper wrapper;

    @Inject
    MorphiaDatastore baseDatastore;

    @Inject
    com.mongodb.client.MongoClient mongoClient;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quantum.database.coordination-database", defaultValue = "quantum-coordination-shared")
    String coordinationDatabase;

    private static Set<Class<?>> mappedTypes(MorphiaDatastore datastore) {
        return datastore.getMapper().getMappedEntities().stream()
            .map(EntityModel::getType)
            .collect(Collectors.toSet());
    }

    private static boolean systemPlane(Class<?> type) {
        return RealmEntityMappingPolicy.isTenantExcludedEntity(
            type, RealmEntityMappingPolicy.DEFAULT_TENANT_EXCLUDED_ENTITY_PACKAGE_PREFIXES, "");
    }

    private static List<String> collections(MorphiaDatastore datastore) {
        return datastore.getDatabase().listCollectionNames().into(new ArrayList<>());
    }

    @Test
    public void baseDatastoreMapsEntitiesFromTheBuildTimeIndex() {
        // Nothing scans the classpath any more: an unindexed module would leave the base datastore empty.
        Assertions.assertFalse(mappedTypes(baseDatastore).isEmpty(), "no entity is mapped on the base datastore");
    }

    /**
     * The entity types the wrapper took from the base datastore when it built its first realm datastore.
     * The base mapper keeps growing as types are mapped on demand, so this snapshot, not the base mapper's
     * current content, is what every realm datastore is built from.
     */
    @SuppressWarnings("unchecked")
    private List<Class<?>> typesHandedToRealms() throws Exception {
        MorphiaDataStoreWrapper target = io.quarkus.arc.ClientProxy.unwrap(wrapper);
        java.lang.reflect.Field field = MorphiaDataStoreWrapper.class.getDeclaredField("cachedMappedEntityTypes");
        field.setAccessible(true);
        return (List<Class<?>>) field.get(target);
    }

    /**
     * A tenant realm maps every entity of the configured packages, system-plane ones included: that is
     * what scanning those packages did, and applications store policy, userProfile and userGroup
     * documents in tenant realms and need their indexes there. A type from outside the configured
     * packages is mapped only when the realm policy allows it.
     */
    @Test
    public void tenantRealmMapsTheSameEntitiesAsAPackageScan() throws Exception {
        String realm = "test-realm-mapping-tenant";
        MorphiaDatastore tenant = wrapper.getDataStore(realm);
        try {
            Set<Class<?>> mapped = mappedTypes(tenant);
            List<String> packages = baseDatastore.getMapper().getConfig().packages();
            List<Class<?>> handed = typesHandedToRealms();
            Assertions.assertFalse(handed.isEmpty());
            for (Class<?> type : handed) {
                boolean expected = RealmEntityMappingPolicy.isInMappedPackages(type, packages) || !systemPlane(type);
                Assertions.assertEquals(expected, mapped.contains(type), "wrong mapping decision in tenant realm for " + type.getName());
            }
        } finally {
            tenant.getDatabase().drop();
        }
    }

    /**
     * When another process holds a realm's index lock, opening the realm waits for it and then builds
     * this process's indexes. It must not return a datastore whose indexes were never applied.
     */
    @Test
    public void waitsForAHeldIndexLockAndThenAppliesIndexes() throws Exception {
        String realm = "test-realm-mapping-lock";
        com.coditory.sherlock.DistributedLock lock = com.coditory.sherlock.mongo.MongoSherlock
            .create(mongoClient.getDatabase(coordinationDatabase).getCollection("locks"))
            .createLock("index-lock-" + realm);
        Assertions.assertTrue(lock.acquire(java.time.Duration.ofSeconds(30)), "could not take the index lock for the test");
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<MorphiaDatastore> opening = executor.submit(() -> wrapper.getDataStore(realm));
            Thread.sleep(1500);
            Assertions.assertFalse(opening.isDone(), "the realm was opened while its index lock was held elsewhere");
            lock.release();
            MorphiaDatastore tenant = opening.get(30, java.util.concurrent.TimeUnit.SECONDS);
            Assertions.assertTrue(hasIndexedCollection(tenant, typesHandedToRealms()), "indexes were not applied after the lock was released");
            tenant.getDatabase().drop();
        } finally {
            lock.release();
            executor.shutdownNow();
        }
    }

    /** Indexes reach the tenant realm for the types it maps, whichever plane they belong to. */
    @Test
    public void tenantRealmGetsIndexes() throws Exception {
        String realm = "test-realm-mapping-indexes";
        MorphiaDatastore tenant = wrapper.getDataStore(realm);
        try {
            List<Class<?>> handed = typesHandedToRealms();
            Assertions.assertTrue(hasIndexedCollection(tenant, handed), "no collection received its indexes");
            List<Class<?>> systemPlane = handed.stream().filter(TestRealmDatastoreMapping::systemPlane).collect(Collectors.toList());
            if (!systemPlane.isEmpty()) {
                Assertions.assertTrue(hasIndexedCollection(tenant, systemPlane), "no system-plane collection received its indexes");
            }
        } finally {
            tenant.getDatabase().drop();
        }
    }

    private static boolean hasIndexedCollection(MorphiaDatastore datastore, List<Class<?>> types) {
        List<String> collections = collections(datastore);
        for (Class<?> type : types) {
            EntityModel model = datastore.getMapper().getEntityModel(type);
            if (model.isAbstract() || model.isInterface() || !collections.contains(model.collectionName())) {
                continue;
            }
            List<String> indexes = new ArrayList<>();
            datastore.getDatabase().getCollection(model.collectionName()).listIndexes()
                .forEach(index -> indexes.add(index.getString("name")));
            if (indexes.size() > 1) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void realmConfigKeepsPackagesAndLeavesIndexingToTheWrapper() {
        String realm = "test-realm-mapping-config";
        MorphiaDatastore tenant = wrapper.getDataStore(realm);
        try {
            Assertions.assertEquals(realm, tenant.getDatabase().getName());
            Assertions.assertEquals(baseDatastore.getMapper().getConfig().packages(), tenant.getMapper().getConfig().packages());
            Assertions.assertFalse(tenant.getMapper().getConfig().applyIndexes(),
                "the realm datastore must not apply indexes on construction, outside the realm lock");
            Assertions.assertFalse(tenant.getMapper().getConfig().applyDocumentValidations());
        } finally {
            tenant.getDatabase().drop();
        }
    }

    @Test
    public void interceptorsApplyToRealmDatastore() {
        String realm = "test-realm-mapping-interceptors";
        MorphiaDatastore tenant = wrapper.getDataStore(realm);
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            Assertions.assertEquals(5, tenant.getMapper().getListeners().size(), "validation, permission, audit, reference and audit-event interceptors");

            ParentModel parent = new ParentModel();
            parent.setRefName("realm-mapping-parent");
            parent.setTestField("realm-mapping-parent");
            tenant.save(parent);

            Assertions.assertNotNull(parent.getAuditInfo(), "the audit interceptor did not run on save");
        } finally {
            tenant.getDatabase().drop();
        }
    }
}
