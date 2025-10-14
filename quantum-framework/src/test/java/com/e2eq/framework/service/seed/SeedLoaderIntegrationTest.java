package com.e2eq.framework.service.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.e2eq.framework.test.MongoDbInitResource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
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
class SeedLoaderIntegrationTest {

    private static final Path SEED_ROOT = Path.of("src", "test", "resources", "seed-packs");
    private static final String REALM = "seed-loader-it";

    @Inject
    MongoClient mongoClient;

    @BeforeEach
    void cleanRealm() {
        mongoClient.getDatabase(REALM).drop();
    }

    @Test
    void seedsDemoPackIntoRealm() {
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

        loader.apply(List.of(SeedPackRef.of("demo-seed")), context);

        MongoCollection<Document> collection = mongoClient.getDatabase(REALM).getCollection("codeLists");
        List<Document> documents = collection.find().sort(Sorts.ascending("code")).into(new ArrayList<>());
        assertEquals(2, documents.size());

        Document newStatus = documents.stream()
                .filter(doc -> "NEW".equals(doc.getString("code")))
                .findFirst()
                .orElseThrow();
        assertEquals("New", newStatus.getString("label"));
        assertEquals("tenant-123", newStatus.getString("tenantId"));
        assertEquals("tenant-123", newStatus.getString("orgRefName"));
        assertEquals("acct-123", newStatus.getString("accountId"));
        assertEquals("owner-123", newStatus.getString("ownerId"));
        assertEquals(REALM, newStatus.getString("realmId"));

        Document registry = mongoClient.getDatabase(REALM)
                .getCollection("_seed_registry")
                .find(Filters.and(
                        Filters.eq("seedPack", "demo-seed"),
                        Filters.eq("version", "1.0.0"),
                        Filters.eq("dataset", "codeLists")))
                .first();
        assertNotNull(registry);
        assertEquals(2, registry.getInteger("records"));

        loader.apply(List.of(SeedPackRef.of("demo-seed")), context);
        assertEquals(2L, collection.countDocuments());
    }
}
