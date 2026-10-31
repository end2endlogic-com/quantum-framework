package com.e2eq.framework.api.tenant;

import java.util.Objects;

/**
 * Declares the ownership and tenant-selection fields for one persisted
 * collection.
 *
 * <p>A pooled tenant purge may operate only on {@link
 * TenantCollectionStorageScope#TENANT_SCOPED} collections. Shared and global
 * collections deliberately have no tenant or expiration field mapping.</p>
 */
public record TenantCollectionMetadata(
    String collectionName,
    TenantCollectionStorageScope storageScope,
    TenantFieldMapping tenantFieldMapping
) {
    public TenantCollectionMetadata {
        collectionName = required(collectionName, "collectionName");
        storageScope = Objects.requireNonNull(storageScope, "storageScope cannot be null");
        if (storageScope == TenantCollectionStorageScope.TENANT_SCOPED) {
            Objects.requireNonNull(
                tenantFieldMapping,
                "tenantFieldMapping is required for a tenant-scoped collection");
        } else if (tenantFieldMapping != null) {
            throw new IllegalArgumentException(
                "tenantFieldMapping is allowed only for a tenant-scoped collection");
        }
    }

    public static TenantCollectionMetadata tenantScoped(
        String collectionName,
        TenantFieldMapping tenantFieldMapping
    ) {
        return new TenantCollectionMetadata(
            collectionName,
            TenantCollectionStorageScope.TENANT_SCOPED,
            tenantFieldMapping);
    }

    public static TenantCollectionMetadata realmShared(String collectionName) {
        return new TenantCollectionMetadata(
            collectionName, TenantCollectionStorageScope.REALM_SHARED, null);
    }

    public static TenantCollectionMetadata systemGlobal(String collectionName) {
        return new TenantCollectionMetadata(
            collectionName, TenantCollectionStorageScope.SYSTEM_GLOBAL, null);
    }

    /**
     * Mongo field paths used to select and atomically mark one tenant's rows.
     * The four data-domain paths intentionally exclude ownerId.
     */
    public record TenantFieldMapping(
        String orgRefNamePath,
        String accountNumPath,
        String tenantIdPath,
        String dataSegmentPath,
        String purgeBatchRefPath,
        String purgeAfterPath
    ) {
        public TenantFieldMapping {
            orgRefNamePath = required(orgRefNamePath, "orgRefNamePath");
            accountNumPath = required(accountNumPath, "accountNumPath");
            tenantIdPath = required(tenantIdPath, "tenantIdPath");
            dataSegmentPath = required(dataSegmentPath, "dataSegmentPath");
            purgeBatchRefPath = required(purgeBatchRefPath, "purgeBatchRefPath");
            purgeAfterPath = required(purgeAfterPath, "purgeAfterPath");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
