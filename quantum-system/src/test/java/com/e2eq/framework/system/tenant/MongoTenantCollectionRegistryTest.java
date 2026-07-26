package com.e2eq.framework.system.tenant;

import com.e2eq.framework.api.tenant.TenantCollectionInventory;
import com.e2eq.framework.api.tenant.TenantCollectionStorageScope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

class MongoTenantCollectionRegistryTest {

    @Test
    void buildsPurgeInventoryFromExplicitCollectionScopes() {
        TenantCollectionInventory inventory =
            MongoTenantCollectionRegistry.buildInventory(
                "shared-orders",
                "shared-orders",
                Set.of("orders", "realmConfiguration", "system.profile"),
                "orders",
                "realmConfiguration",
                "");

        Assertions.assertEquals(
            Set.of("orders", "realmConfiguration"),
            inventory.persistedCollectionNames());
        Assertions.assertEquals(1, inventory.tenantPurgeCollections().size());
        Assertions.assertEquals(
            TenantCollectionStorageScope.TENANT_SCOPED,
            inventory.tenantPurgeCollections().get(0).storageScope());
        Assertions.assertEquals(
            "dataDomain.tenantId",
            inventory.tenantPurgeCollections().get(0)
                .tenantFieldMapping().tenantIdPath());
    }

    @Test
    void failsClosedWhenAUserCollectionIsNotConfigured() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            MongoTenantCollectionRegistry.buildInventory(
                "shared-orders",
                "shared-orders",
                Set.of("orders", "unknownCollection"),
                "orders",
                "",
                ""));
    }

    @Test
    void failsClosedWhenACollectionHasMoreThanOneScope() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            MongoTenantCollectionRegistry.buildInventory(
                "shared-orders",
                "shared-orders",
                Set.of("orders"),
                "orders",
                " orders ",
                ""));
    }

    @Test
    void keepsBlobCollectionsInTheRequiredInventory() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            MongoTenantCollectionRegistry.buildInventory(
                "shared-orders",
                "shared-orders",
                Set.of("orders", "tenantFiles.files", "tenantFiles.chunks"),
                "orders",
                "",
                ""));
    }
}
