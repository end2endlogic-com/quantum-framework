package com.e2eq.framework.api.tenant;

/**
 * Destructive lifecycle strategy derived from tenant placement.
 */
public enum TenantDecommissionStrategy {
    /** Verify a realm/database archive, then drop the dedicated database. */
    ARCHIVE_AND_DROP_REALM,

    /**
     * Verify a tenant-scoped archive, revoke tenant access, then stamp all
     * tenant-owned rows with one purge batch and expiration time.
     */
    ARCHIVE_AND_EXPIRE_TENANT_DATA
}
