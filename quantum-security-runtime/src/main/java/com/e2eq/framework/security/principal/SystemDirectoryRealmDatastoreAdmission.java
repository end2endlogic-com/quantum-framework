package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.morphia.RealmDatastoreAdmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The realm catalog as this service sees it (its {@link SystemDirectory}, local or remote), for
 * the datastore layer's check before it opens a datastore for a realm with no database.
 */
@ApplicationScoped
public class SystemDirectoryRealmDatastoreAdmission implements RealmDatastoreAdmission {

    @Inject
    Instance<SystemDirectory> systemDirectory;

    @Override
    public boolean registered(String realm) {
        if (systemDirectory == null || !systemDirectory.isResolvable()) {
            return false;
        }
        String trimmed = realm == null ? null : realm.trim();
        // Exact match: a datastore is opened for the registered refName itself, never another case.
        return RealmRefNames.registered(systemDirectory.get(), trimmed).map(trimmed::equals).orElse(false);
    }
}
