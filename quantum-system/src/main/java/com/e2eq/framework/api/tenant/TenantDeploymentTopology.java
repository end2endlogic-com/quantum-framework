package com.e2eq.framework.api.tenant;

/**
 * MongoDB placement and lifecycle topology for tenant provisioning.
 *
 * <p>A realm selects a logical Mongo database and owns its schema lifecycle.
 * A tenant's {@code DataDomain} remains the visibility boundary in either
 * topology.</p>
 */
public enum TenantDeploymentTopology {
    /**
     * One tenant owns one realm/database. Provisioning may create the realm and
     * run realm-level migrations and index initialization.
     */
    DEDICATED_REALM,

    /**
     * Many tenants participate in one pre-provisioned realm/database. Tenant
     * admission must not create, migrate, initialize, or drop the shared realm.
     */
    POOLED_REALM
}
