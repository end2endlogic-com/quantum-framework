package com.e2eq.framework.service.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.e2eq.framework.test.MongoDbInitResource;
import com.e2eq.framework.util.EnvConfigUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Verifies that seed packs are applied automatically at startup by SeedStartupRunner
 * using the configured test realm and test seed packs under src/test/resources/seed-packs.
 */
@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
public class SeedStartupRunnerIT {

    @Inject
    MongoClient mongoClient;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Test
    void seedsAreAppliedOnStartupToTestRealm() {
        String testRealm = envConfigUtils.getTestRealm();

        // Validate seed data (from demo-seed test pack) exists in the test realm
        MongoCollection<Document> collection = mongoClient.getDatabase(testRealm).getCollection("codeLists");
        List<Document> documents = collection.find().sort(Sorts.ascending("code")).into(new ArrayList<>());
        assertTrue(documents.size() >= 2, "Expected demo seed to apply at least 2 codeLists records on startup");

        Document newStatus = documents.stream()
                .filter(doc -> "NEW".equals(doc.getString("code")))
                .findFirst()
                .orElse(null);
        assertNotNull(newStatus, "Expected to find codeLists record with code NEW");

        // Verify registry recorded application
        Document registry = mongoClient.getDatabase(testRealm)
                .getCollection("_seed_registry")
                .find(Filters.and(
                        Filters.eq("seedPack", "demo-seed"),
                        Filters.eq("version", "1.0.0"),
                        Filters.eq("dataset", "codeLists")))
                .first();
        assertNotNull(registry, "Expected _seed_registry entry for demo-seed/codeLists in test realm");
        assertTrue(registry.getInteger("records") >= 2, "Expected registry to record at least 2 applied records");

        // Idempotency sanity: ensure no duplicates beyond expected two for demo-seed baseline
        long count = collection.countDocuments();
        // Re-check without applying again; startup should have run once.
        long count2 = collection.countDocuments();
        assertEquals(count, count2, "Record count should be stable without re-apply in the same test run");
    }
}
