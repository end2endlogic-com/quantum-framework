package com.e2eq.framework.model.persistent.morphia;

/**
 * A datastore was asked for a realm that no registered realm names and that has no database
 * ({@link MorphiaDataStoreWrapper}). Nothing was opened or created. The REST layer answers it 403
 * {@code REALM_NOT_AUTHORIZED}.
 */
public class UnregisteredRealmException extends RuntimeException {

    private final String realm;

    public UnregisteredRealmException(String realm) {
        super("Realm '" + realm + "' is not registered; no datastore is opened for it");
        this.realm = realm;
    }

    public String realm() {
        return realm;
    }
}
