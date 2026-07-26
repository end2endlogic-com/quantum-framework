package com.e2eq.framework.model.security;

/**
 * Persistence topology owned by a realm.
 *
 * <p>Only an explicitly shared realm may contain more than one tenant. Realm
 * records created before this field existed are treated as dedicated.</p>
 */
public enum RealmDeploymentType {
    DEDICATED,
    SHARED
}
