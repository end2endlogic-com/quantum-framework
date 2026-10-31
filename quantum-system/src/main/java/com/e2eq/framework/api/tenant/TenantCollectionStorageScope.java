package com.e2eq.framework.api.tenant;

/**
 * Ownership boundary for a persisted collection in a realm database.
 */
public enum TenantCollectionStorageScope {
    /**
     * Documents are owned by one tenant and may be selected by the canonical
     * tenant data-domain fields.
     */
    TENANT_SCOPED,

    /**
     * Documents are shared by tenants in the realm and must never be included
     * in an individual tenant purge.
     */
    REALM_SHARED,

    /**
     * Documents belong to the system management plane and must never be
     * included in a tenant purge.
     */
    SYSTEM_GLOBAL
}
