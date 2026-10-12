package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.system.catalog.RealmRegistered;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Registering a realm drops a "not registered" answer cached before provisioning. */
class RealmOntologyKeyResolverTest {

    private static final DataDomain TENANT = new DataDomain("acme.com", "0000000001", "acme.com", 0, "admin@acme.com");

    @AfterEach
    void reset() {
        RealmOntologyKeys.uninstall();
    }

    @Test
    void registeringARealmDropsItsCachedLegacyKey() {
        AtomicBoolean provisioned = new AtomicBoolean();
        RealmOntologyKeys.install(realm -> provisioned.get() ? Optional.of(TENANT) : Optional.empty(), (r, k) -> { });
        assertEquals("acme", RealmOntologyKeys.forRealm("acme").getTenantId(), "looked up before provisioning");

        provisioned.set(true);
        new RealmOntologyKeyResolver().onRealmRegistered(new RealmRegistered("acme"));

        assertEquals("acme.com", RealmOntologyKeys.forRealm("acme").getTenantId(),
                "the registered key is used at once, not after the recheck interval");
    }
}
