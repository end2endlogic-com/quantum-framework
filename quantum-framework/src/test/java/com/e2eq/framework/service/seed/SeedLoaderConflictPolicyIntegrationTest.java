package com.e2eq.framework.service.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.e2eq.framework.test.MongoDbInitResource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
class SeedLoaderConflictPolicyIntegrationTest {

    private static final Path SEED_ROOT = Path.of("src", "test", "resources", "seed-packs");
    private static final String REALM = "seed-loader-conflict-it";

    @Inject
    MongoClient mongoClient;

    @BeforeEach
    void cleanRealm() {
        mongoClient.getDatabase(REALM).drop();
    }

    private void insertRegistryChecksum(String checksum) {
        Document doc = new Document()
                .append("seedPack", "demo-seed")
                .append("version", "1.0.0")
                .append("dataset", "codeLists-demo")
                .append("checksum", checksum)
                .append("records", 999)
                .append("appliedToRealm", REALM)
                .append("appliedAt", Instant.now());
        mongoClient.getDatabase(REALM)
                .getCollection("_seed_registry")
                .replaceOne(Filters.and(
                                Filters.eq("seedPack", "demo-seed"),
                                Filters.eq("version", "1.0.0"),
                                Filters.eq("dataset", "codeLists-demo")),
                        doc,
                        new ReplaceOptions().upsert(true));
    }

    private SeedLoader newLoader(SeedConflictPolicy policy) {
        return SeedLoader.builder()
                .addSeedSource(new FileSeedSource("test", SEED_ROOT))
                .seedRepository(new MongoSeedRepository(mongoClient))
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .conflictPolicy(policy)
                .build();
    }

    private SeedContext newContext() {
        return SeedContext.builder(REALM)
                .tenantId("tenant-123")
                .orgRefName("tenant-123")
                .accountId("acct-123")
                .ownerId("owner-123")
                .build();
    }

    @Test
    void existingWins_skipsApply_whenChecksumConflict() {
        // Arrange: pre-populate DB with modified value and conflicting registry checksum
        MongoCollection<Document> collection = mongoClient.getDatabase(REALM).getCollection("codeLists-demo");
        Document dd = new Document("tenantId", "tenant-123")
                .append("orgRefName", "tenant-123")
                .append("accountNum", "acct-123")
                .append("ownerId", "owner-123");
        collection.insertOne(new Document("code", "NEW")
                .append("codeListName", "shipmentStatus")
                .append("label", "User Changed")
                .append("realmId", REALM)
                .append("dataDomain", dd));
        insertRegistryChecksum("bogus-old-checksum"); // ensure conflict

        SeedLoader loader = newLoader(SeedConflictPolicy.EXISTING_WINS);
        SeedContext context = newContext();

        // Act
        loader.apply(List.of(SeedPackRef.of("demo-seed")), context);

        // Assert: value was NOT overwritten by seed (EXISTING_WINS)
        Document found = collection.find(Filters.eq("code", "NEW")).first();
        assertNotNull(found);
        assertEquals("User Changed", found.getString("label"));

        // And registry should remain with previous checksum (no update to match current dataset)
        Document reg = mongoClient.getDatabase(REALM)
                .getCollection("_seed_registry")
                .find(Filters.and(
                        Filters.eq("seedPack", "demo-seed"),
                        Filters.eq("version", "1.0.0"),
                        Filters.eq("dataset", "codeLists-demo")))
                .first();
        assertNotNull(reg);
        assertEquals("bogus-old-checksum", reg.getString("checksum"));
    }

    @Test
    void errorPolicy_throws_whenChecksumConflict() {
        insertRegistryChecksum("something-else");
        SeedLoader loader = newLoader(SeedConflictPolicy.ERROR);
        SeedContext context = newContext();

        assertThrows(SeedLoadingException.class, () -> loader.apply(List.of(SeedPackRef.of("demo-seed")), context));
    }

    @Test
    void seedWins_overwrites_whenChecksumConflict() {
        // Arrange: change existing value and pre-insert conflicting checksum
        MongoCollection<Document> collection = mongoClient.getDatabase(REALM).getCollection("codeLists-demo");
        Document dd = new Document("tenantId", "tenant-123")
                .append("orgRefName", "tenant-123")
                .append("accountNum", "acct-123")
                .append("ownerId", "owner-123");
        collection.insertOne(new Document("code", "NEW")
                .append("codeListName", "shipmentStatus")
                .append("label", "User Changed")
                .append("realmId", REALM)
                .append("dataDomain", dd));
        insertRegistryChecksum("conflicting-checksum");

        SeedLoader loader = newLoader(SeedConflictPolicy.SEED_WINS);
        SeedContext context = newContext();

        // Act
        loader.apply(List.of(SeedPackRef.of("demo-seed")), context);

        // Assert: should match seed value now
        Document found = collection.find(Filters.eq("code", "NEW")).first();
        assertNotNull(found);
        assertEquals("New", found.getString("label"));

        // Registry should now be updated to current checksum and records=2
        Document reg = mongoClient.getDatabase(REALM)
                .getCollection("_seed_registry")
                .find(Filters.and(
                        Filters.eq("seedPack", "demo-seed"),
                        Filters.eq("version", "1.0.0"),
                        Filters.eq("dataset", "codeLists-demo")))
                .first();
        assertNotNull(reg);
        // We can't easily know the checksum in test; just assert records updated to 2
        assertEquals(2, reg.getInteger("records").intValue());

        // And collection should contain both records from the seed
        List<Document> docs = collection.find().into(new ArrayList<>());
        assertEquals(2, docs.size());
    }
}
