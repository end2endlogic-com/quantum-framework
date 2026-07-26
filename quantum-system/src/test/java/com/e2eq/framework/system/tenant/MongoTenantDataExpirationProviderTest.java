package com.e2eq.framework.system.tenant;

import com.e2eq.framework.api.tenant.TenantCollectionMetadata;
import com.e2eq.framework.api.tenant.TenantDataScope;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MongoTenantDataExpirationProviderTest {

    @Test
    void buildsCanonicalTenantSelectorWithoutOwnerId() {
        TenantCollectionMetadata.TenantFieldMapping fields =
            MongoTenantCollectionRegistry.DEFAULT_TENANT_FIELDS;
        BsonDocument filter = MongoTenantDataExpirationProvider.tenantFilter(
            new TenantDataScope("acme", "100", "acme-com", 2),
            fields).toBsonDocument(org.bson.Document.class,
                com.mongodb.MongoClientSettings.getDefaultCodecRegistry());

        String json = filter.toJson();
        Assertions.assertTrue(json.contains("dataDomain.orgRefName"));
        Assertions.assertTrue(json.contains("dataDomain.accountNum"));
        Assertions.assertTrue(json.contains("dataDomain.tenantId"));
        Assertions.assertTrue(json.contains("dataDomain.dataSegment"));
        Assertions.assertFalse(json.contains("ownerId"));
    }

    @Test
    void ttlIndexNameIsStableForConfiguredFieldPath() {
        Assertions.assertEquals(
            "quantum_tenant_purge_ttl_lifecycle_purgeAfter",
            MongoTenantDataExpirationProvider.ttlIndexName(
                "lifecycle.purgeAfter"));
    }
}
