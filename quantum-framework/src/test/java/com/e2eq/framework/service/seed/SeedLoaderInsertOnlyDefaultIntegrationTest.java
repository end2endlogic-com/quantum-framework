package com.e2eq.framework.service.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.e2eq.framework.test.MongoDbInitResource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
@io.quarkus.test.junit.TestProfile(SeedNoHttpTestProfile.class)
class SeedLoaderInsertOnlyDefaultIntegrationTest {

    private static final Path SEED_ROOT = Path.of("src", "test", "resources", "seed-packs");
    private static final String REALM = "seed-loader-insert-only-it";

    @Inject
    MongoClient mongoClient;

    @BeforeEach
    void cleanRealm() {
        mongoClient.getDatabase(REALM).drop();
    }

    @Test
    void default_is_insert_only_skips_existing_by_natural_key() {
        // Arrange: Pre-insert one record with same natural key but different label to simulate user change
        MongoCollection<Document> collection = mongoClient.getDatabase(REALM).getCollection("codeLists-demo");
        Document dd = new Document("tenantId", "tenant-123")
                .append("orgRefName", "tenant-123")
                .append("accountNum", "acct-123")
                .append("ownerId", "owner-123");
        collection.insertOne(new Document("codeListName", "shipmentStatus")
                .append("code", "NEW")
                .append("label", "User Changed")
                .append("realmId", REALM)
                .append("dataDomain", dd));

        SeedLoader loader = SeedLoader.builder()
                .addSeedSource(new FileSeedSource("test", SEED_ROOT))
                .seedRepository(new MongoSeedRepository(mongoClient))
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .build();

        SeedContext context = SeedContext.builder(REALM)
                .tenantId("tenant-123")
                .orgRefName("tenant-123")
                .accountId("acct-123")
                .ownerId("owner-123")
                .build();

        // Act: Apply the seed pack that does NOT specify upsert (defaults to insert-only)
        loader.apply(List.of(SeedPackRef.of("demo-insert-only")), context);

        // Assert: existing record was NOT overwritten
        Document found = collection.find(Filters.and(
                Filters.eq("codeListName", "shipmentStatus"),
                Filters.eq("code", "NEW"))).first();
        assertNotNull(found);
        assertEquals("User Changed", found.getString("label"));

        // And the other record from seed should have been inserted
        Document found2 = collection.find(Filters.and(
                Filters.eq("codeListName", "shipmentStatus"),
                Filters.eq("code", "IN_PROGRESS"))).first();
        assertNotNull(found2);
        assertEquals("In Progress", found2.getString("label"));

        // Collection should have exactly 2 documents (one preexisting + one added)
        List<Document> all = collection.find().into(new ArrayList<>());
        assertEquals(2, all.size());
    }
}
