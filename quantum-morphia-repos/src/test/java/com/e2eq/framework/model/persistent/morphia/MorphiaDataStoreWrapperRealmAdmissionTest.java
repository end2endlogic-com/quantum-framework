package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.util.EnvConfigUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoIterable;
import dev.morphia.MorphiaDatastore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Round-5 review: a caller-supplied realm name opened, and indexed, a datastore of that name,
 * creating a MongoDB database per name (271 in the probe). A datastore now opens only for a realm
 * that exists or is registered.
 */
class MorphiaDataStoreWrapperRealmAdmissionTest {

    private MorphiaDataStoreWrapper wrapper;
    private Document registeredRealmRow;
    private boolean catalogRegisters;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        wrapper = new MorphiaDataStoreWrapper();
        wrapper.envConfigUtils = mock(EnvConfigUtils.class);
        when(wrapper.envConfigUtils.getSystemRealm()).thenReturn("system-com");
        when(wrapper.envConfigUtils.getDefaultRealm()).thenReturn("default-com");
        when(wrapper.envConfigUtils.getTestRealm()).thenReturn("test-system-com");
        wrapper.mongoClient = mock(MongoClient.class, RETURNS_DEEP_STUBS);
        MongoIterable<String> names = mock(MongoIterable.class);
        List<String> existing = List.of("system-com", "acme-existing");
        when(names.iterator()).thenAnswer(call -> {
            java.util.Iterator<String> fresh = existing.iterator();
            MongoCursor<String> c = mock(MongoCursor.class);
            when(c.hasNext()).thenAnswer(x -> fresh.hasNext());
            when(c.next()).thenAnswer(x -> fresh.next());
            return c;
        });
        when(wrapper.mongoClient.listDatabaseNames()).thenReturn(names);
        when(wrapper.mongoClient.getDatabase("system-com").getCollection(any()).find(any(org.bson.conversions.Bson.class))
                .limit(1).first()).thenAnswer(call -> registeredRealmRow);
        wrapper.dataStore = mock(MorphiaDatastore.class, RETURNS_DEEP_STUBS);
        wrapper.realmAdmission = null;
    }

    @Test
    void anUnregisteredRealmWithNoDatabaseIsRefused() {
        assertThrows(UnregisteredRealmException.class, () -> wrapper.requireAdmitted("zz-flood-1-abcd"));
        assertThrows(UnregisteredRealmException.class, () -> wrapper.requireAdmitted("bad.realm"));
    }

    @Test
    void theSystemRealmAndAnExistingDatabaseAreAdmitted() {
        assertDoesNotThrow(() -> wrapper.requireAdmitted("system-com"));
        assertDoesNotThrow(() -> wrapper.requireAdmitted("acme-existing"));
    }

    @Test
    void aRealmRegisteredInTheSystemRealmCatalogIsAdmitted() {
        registeredRealmRow = new Document("refName", "acme-new");
        assertDoesNotThrow(() -> wrapper.requireAdmitted("acme-new"));
    }

    @Test
    void aRealmBeingProvisionedOrMatchingTheTestPatternIsAdmitted() throws Exception {
        try (AutoCloseable ignored = MorphiaDataStoreWrapper.provisioning("acme-provisioning")) {
            assertDoesNotThrow(() -> wrapper.requireAdmitted("acme-provisioning"));
        }
        assertThrows(UnregisteredRealmException.class, () -> wrapper.requireAdmitted("acme-provisioning"));
        wrapper.unregisteredRealmPattern = Optional.of("test-.*");
        assertDoesNotThrow(() -> wrapper.requireAdmitted("test-anything"));
    }

    @Test
    void theConfiguredDefaultAndTestRealmsAreAdmitted() {
        assertDoesNotThrow(() -> wrapper.requireAdmitted("default-com"));
        assertDoesNotThrow(() -> wrapper.requireAdmitted("test-system-com"));
    }
}
