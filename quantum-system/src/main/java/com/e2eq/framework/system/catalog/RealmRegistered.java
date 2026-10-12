package com.e2eq.framework.system.catalog;

/**
 * Fired (synchronously, as a CDI event) by {@link RealmCatalogService#register} after a realm
 * catalog entry is created or updated, so components that cached a lookup of the realm (including
 * a "not registered" answer) can drop it before the caller goes on to use the realm.
 *
 * @param refName the registered realm's reference name
 */
public record RealmRegistered(String refName) {
}
