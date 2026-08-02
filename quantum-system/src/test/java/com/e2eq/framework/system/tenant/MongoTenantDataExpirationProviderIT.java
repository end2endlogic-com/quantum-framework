package com.e2eq.framework.system.tenant;

import com.e2eq.framework.api.tenant.TenantCollectionInventory;
import com.e2eq.framework.api.tenant.TenantCollectionMetadata;
import com.e2eq.framework.api.tenant.TenantDataExpirationProvider;
import com.e2eq.framework.api.tenant.TenantDataScope;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class MongoTenantDataExpirationProviderIT {

    @Test
    void stampsOnlyRequestedTenantAndCreatesAbsoluteTimeTtlIndex() {
        String databaseName = "test-tenant-expiration-it-"
            + UUID.randomUUID().toString().replace("-", "");
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017")) {
            var collection = client.getDatabase(databaseName).getCollection("orders");
            collection.insertMany(List.of(
                tenantDocument("a", "100", "tenant-a", 0),
                tenantDocument("b", "200", "tenant-b", 0)));

            MongoTenantDataExpirationProvider provider =
                new MongoTenantDataExpirationProvider();
            provider.mongoClient = client;
            provider.collectionRegistry = (realmId, db) ->
                new TenantCollectionInventory(
                    realmId,
                    db,
                    Set.of("orders"),
                    List.of(TenantCollectionMetadata.tenantScoped(
                        "orders",
                        MongoTenantCollectionRegistry.DEFAULT_TENANT_FIELDS)));

            Instant purgeAfter = Instant.parse("2099-01-01T00:00:00Z");
            TenantDataExpirationProvider.ExpirationManifest result =
                provider.stampAndVerify(
                    new TenantDataExpirationProvider.ExpirationRequest(
                        "run-1",
                        "shared-orders",
                        databaseName,
                        "orders",
                        new TenantDataScope("a", "100", "tenant-a", 0),
                        "purge-1",
                        purgeAfter));

            Assertions.assertTrue(result.coverageVerified());
            Assertions.assertEquals(1, result.matchedDocumentCount());
            Assertions.assertEquals(1, collection.countDocuments(
                new Document("purgeBatchRef", "purge-1")));
            Assertions.assertEquals(0, collection.countDocuments(
                new Document("dataDomain.tenantId", "tenant-b")
                    .append("purgeBatchRef", "purge-1")));

            Document ttlIndex = collection.listIndexes()
                .into(new java.util.ArrayList<>())
                .stream()
                .filter(index -> MongoTenantDataExpirationProvider
                    .ttlIndexName("purgeAfter").equals(index.getString("name")))
                .findFirst()
                .orElseThrow();
            Assertions.assertEquals(
                0L,
                ((Number) ttlIndex.get("expireAfterSeconds")).longValue());
        } finally {
            try (MongoClient cleanup = MongoClients.create("mongodb://localhost:27017")) {
                cleanup.getDatabase(databaseName).drop();
            }
        }
    }

    private static Document tenantDocument(
        String org,
        String account,
        String tenant,
        int segment
    ) {
        return new Document("dataDomain", new Document("orgRefName", org)
            .append("accountNum", account)
            .append("tenantId", tenant)
            .append("dataSegment", segment));
    }
}
