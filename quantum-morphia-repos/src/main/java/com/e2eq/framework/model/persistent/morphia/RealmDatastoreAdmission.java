package com.e2eq.framework.model.persistent.morphia;

/**
 * Whether the realm catalog registers {@code realm}, asked by {@link MorphiaDataStoreWrapper} before
 * it opens (and indexes) a datastore for a realm that has no database yet. Provided by the security
 * runtime from the service's {@code SystemDirectory} (local or remote); absent, only this service's
 * own system realm catalog is consulted.
 */
public interface RealmDatastoreAdmission {

    /**
     * @return true when the catalog registers {@code realm}
     * @throws RuntimeException when the catalog cannot answer (the datastore is then not opened)
     */
    boolean registered(String realm);
}
