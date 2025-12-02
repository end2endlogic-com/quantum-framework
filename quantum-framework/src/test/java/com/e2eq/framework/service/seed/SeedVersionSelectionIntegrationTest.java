package com.e2eq.framework.service.seed;

import com.e2eq.framework.test.MongoDbInitResource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
@io.quarkus.test.junit.TestProfile(SeedNoHttpTestProfile.class)
public class SeedVersionSelectionIntegrationTest {

    private static final Path SEED_ROOT = Path.of("src", "test", "resources", "seed-packs");
    private static final String REALM = "seed-version-it";

    @Inject
    MongoClient mongoClient;

    @BeforeEach
    void cleanRealm() {
        mongoClient.getDatabase(REALM).drop();
    }

    @Test
    void latestVersionIsSelectedWhenNoSpecIsGiven() {
        SeedLoader loader = SeedLoader.builder()
                .addSeedSource(new FileSeedSource("test", SEED_ROOT))
                .seedRepository(new MongoSeedRepository(mongoClient))
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .build();

        SeedContext context = SeedContext.builder(REALM).build();
        loader.apply(java.util.List.of(SeedPackRef.of("multi-version")), context);

        MongoCollection<Document> coll = mongoClient.getDatabase(REALM).getCollection("verifications");
        Document doc = coll.find().first();
        assertEquals("v1.1.0", doc.getString("label"));
    }
}
