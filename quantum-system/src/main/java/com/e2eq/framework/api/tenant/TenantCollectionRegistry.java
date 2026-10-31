package com.e2eq.framework.api.tenant;

/**
 * Supplies the reconciled collection inventory used to plan pooled tenant
 * archive and expiration operations.
 */
public interface TenantCollectionRegistry {

    TenantCollectionInventory inventory(String realmId, String databaseName);
}
