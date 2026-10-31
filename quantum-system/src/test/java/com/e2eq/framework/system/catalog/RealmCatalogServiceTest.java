package com.e2eq.framework.system.catalog;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.*;

/** Registering a realm announces it, after it is saved, so cached lookups of it are dropped. */
class RealmCatalogServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void registerFiresRealmRegisteredAfterTheRealmIsSaved() {
        RealmCatalogService catalog = new RealmCatalogService();
        catalog.systemDirectory = mock(SystemDirectory.class);
        catalog.realmRegistered = mock(Event.class);
        Realm realm = new Realm();
        realm.setRefName("acme");
        when(catalog.systemDirectory.registerRealm(realm)).thenReturn(realm);

        catalog.register(realm);

        InOrder order = inOrder(catalog.systemDirectory, catalog.realmRegistered);
        order.verify(catalog.systemDirectory).registerRealm(realm);
        order.verify(catalog.realmRegistered).fire(new RealmRegistered("acme"));
    }
}
