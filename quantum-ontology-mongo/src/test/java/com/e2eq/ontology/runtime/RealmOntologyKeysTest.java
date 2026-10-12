package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The ontology key of a realm is its registered domain; unregistered realms keep the realm-name key. */
class RealmOntologyKeysTest {

    private static final DataDomain TENANT = new DataDomain("acme.com", "0000000001", "acme.com", 0, "admin@acme.com");

    @AfterEach
    void reset() {
        RealmOntologyKeys.uninstall();
    }

    @Test
    void withoutARegistryEveryFieldIsTheRealmName() {
        DataDomain key = RealmOntologyKeys.forRealm("acme");
        assertEquals("acme", key.getTenantId());
        assertEquals("acme", key.getOrgRefName());
        assertEquals("acme", key.getAccountNum());
        assertEquals(0, key.getDataSegment());
    }

    @Test
    void aRegisteredRealmUsesItsTenantAndTheSameKeyForEveryCaller() {
        RealmOntologyKeys.install(realm -> "acme".equals(realm) ? Optional.of(TENANT) : Optional.empty(), (r, k) -> { });
        DataDomain key = RealmOntologyKeys.forRealm("acme");
        assertEquals("acme.com", key.getTenantId());
        assertEquals("acme.com", key.getOrgRefName());
        assertEquals("0000000001", key.getAccountNum());
        assertEquals("acme", key.getOwnerId(), "the owner is fixed, so the key never depends on who resolved it");
        assertEquals("other", RealmOntologyKeys.forRealm("other").getTenantId(), "unregistered realms keep the legacy key");
    }

    @Test
    void legacyTBoxesAreMovedOnceWhenTheRegisteredKeyIsFirstUsed() {
        List<String> moved = new ArrayList<>();
        RealmOntologyKeys.install(realm -> Optional.of(TENANT), (realm, key) -> moved.add(realm + "->" + key.getTenantId()));
        RealmOntologyKeys.forRealm("acme");
        RealmOntologyKeys.forRealm("acme");
        assertEquals(List.of("acme->acme.com"), moved);
    }

    @Test
    void aFailedLegacyMoveRunsAgainOnTheNextLookup() {
        List<String> moved = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        RealmOntologyKeys.install(realm -> Optional.of(TENANT), (realm, key) -> {
            if (attempts.incrementAndGet() == 1)
                throw new IllegalStateException("mongo blip");
            moved.add(realm + "->" + key.getTenantId());
        });
        assertEquals("acme.com", RealmOntologyKeys.forRealm("acme").getTenantId(), "the key does not wait on the move");
        assertTrue(moved.isEmpty());
        RealmOntologyKeys.forRealm("acme");
        RealmOntologyKeys.forRealm("acme");
        assertEquals(List.of("acme->acme.com"), moved, "retried once on the next lookup, then done");
        assertEquals(2, attempts.get());
    }

    @Test
    void anIncompleteRegistrationKeepsTheLegacyKey() {
        RealmOntologyKeys.install(realm -> Optional.of(new DataDomain("acme.com", null, "acme.com", 0, null)), (r, k) -> fail("no move"));
        assertEquals("acme", RealmOntologyKeys.forRealm("acme").getTenantId());
    }

    @Test
    void aRegistryOutageNeverMovesAKnownRealmToAnotherKey() {
        AtomicInteger calls = new AtomicInteger();
        RealmOntologyKeys.install(realm -> {
            if (calls.incrementAndGet() > 1)
                throw new IllegalStateException("registry down");
            return Optional.of(TENANT);
        }, (r, k) -> { });
        assertEquals("acme.com", RealmOntologyKeys.forRealm("acme").getTenantId());
        assertEquals("acme.com", RealmOntologyKeys.forRealm("acme").getTenantId(), "registered keys are cached");
    }

    @Test
    void aFailedLookupIsNotRememberedAsUnregistered() {
        AtomicInteger calls = new AtomicInteger();
        RealmOntologyKeys.install(realm -> {
            if (calls.incrementAndGet() == 1)
                throw new IllegalStateException("no token yet");
            return Optional.of(TENANT);
        }, (r, k) -> { });
        assertEquals("acme", RealmOntologyKeys.forRealm("acme").getTenantId(), "legacy while the registry cannot answer");
        assertEquals("acme.com", RealmOntologyKeys.forRealm("acme").getTenantId(), "asked again, not pinned to legacy");
    }

    @Test
    void callersCannotChangeTheCachedKey() {
        RealmOntologyKeys.install(realm -> Optional.of(TENANT), (r, k) -> { });
        RealmOntologyKeys.forRealm("acme").setTenantId("tampered");
        assertEquals("acme.com", RealmOntologyKeys.forRealm("acme").getTenantId());
    }
}
