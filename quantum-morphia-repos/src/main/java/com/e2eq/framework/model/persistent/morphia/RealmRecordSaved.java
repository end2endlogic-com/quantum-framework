package com.e2eq.framework.model.persistent.morphia;

/**
 * Fired (synchronously, as a CDI event) after a {@code Realm} row is saved through any repository
 * or datastore path: registration, approval, bootstrap, seed changesets. Components that cached a
 * realm lookup (a "not registered" answer included) drop it, so the realm resolves at once.
 *
 * @param refName the saved realm's reference name
 */
public record RealmRecordSaved(String refName) {
}
