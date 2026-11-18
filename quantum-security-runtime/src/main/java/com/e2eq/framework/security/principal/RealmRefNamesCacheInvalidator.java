package com.e2eq.framework.security.principal;

import com.e2eq.framework.system.catalog.RealmRegistered;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** Drops {@link RealmRefNames}' cached answers when a realm is registered, so it resolves at once. */
@ApplicationScoped
public class RealmRefNamesCacheInvalidator {

    void onRealmRegistered(@Observes RealmRegistered event) {
        RealmRefNames.forget();
    }
}
