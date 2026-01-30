package com.e2eq.framework.service.seed;

import com.e2eq.framework.test.MongoDbInitResource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for StringInterpolationTransform with SPI-based variable resolution.
 */
@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
@io.quarkus.test.junit.TestProfile(SeedNoHttpTestProfile.class)
public class StringInterpolationIntegrationTest {

    private static final Path SEED_ROOT = Path.of("src", "test", "resources", "seed-packs");
    private static final String REALM = "interpolation-test-realm";

    @Inject
    MongoClient mongoClient;

    @BeforeEach
    void cleanRealm() {
        mongoClient.getDatabase(REALM).drop();
    }

    @Test
    void stringInterpolationResolvesContextVariables() {
        // Build loader with default transforms (includes stringInterpolation)
        SeedLoader loader = SeedLoader.builder()
                .addSeedSource(new FileSeedSource("test", SEED_ROOT))
                .seedRepository(new MongoSeedRepository(mongoClient))
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .build();

        SeedContext context = SeedContext.builder(REALM)
                .tenantId("acme-corp")
                .orgRefName("acme-org")
                .ownerId("owner-123")
                .accountId("account-456")
                .build();

        // Apply the interpolation-seed pack
        loader.apply(List.of(SeedPackRef.of("interpolation-seed")), context);

        // Verify the records were inserted with interpolated values
        MongoCollection<Document> rules = mongoClient.getDatabase(REALM).getCollection("securityRules");
        List<Document> docs = rules.find().sort(Sorts.ascending("refName")).into(new ArrayList<>());

        assertEquals(4, docs.size(), "Expected 4 securityRules records");

        // rule1: runAsUserId="admin@{tenantId}" -> "admin@acme-corp"
        Document rule1 = findByRefName(docs, "rule1");
        assertEquals("admin@acme-corp", rule1.getString("runAsUserId"),
                "tenantId should be interpolated in runAsUserId");
        assertEquals(REALM, rule1.getString("realm"),
                "realm should be interpolated");
        assertEquals("Rule for acme-corp", rule1.getString("description"),
                "tenantId should be interpolated in description");

        // rule2: runAsUserId="system@{orgRefName}" -> "system@acme-org"
        //        config.owner="{ownerId}" -> "owner-123"
        //        config.account="{accountId}" -> "account-456"
        Document rule2 = findByRefName(docs, "rule2");
        assertEquals("system@acme-org", rule2.getString("runAsUserId"),
                "orgRefName should be interpolated in runAsUserId");
        Document config = (Document) rule2.get("config");
        assertNotNull(config, "config should be present");
        assertEquals("owner-123", config.getString("owner"),
                "ownerId should be interpolated in nested config.owner");
        assertEquals("account-456", config.getString("account"),
                "accountId should be interpolated in nested config.account");

        // rule3: No variables, should remain unchanged
        Document rule3 = findByRefName(docs, "rule3");
        assertEquals("novar@example.com", rule3.getString("runAsUserId"),
                "No interpolation needed, value should remain unchanged");
        assertEquals("No variables here", rule3.getString("description"));

        // rule4: Unknown variable, should remain as-is when failOnMissing=false
        Document rule4 = findByRefName(docs, "rule4");
        assertEquals("{unknownVar}", rule4.getString("runAsUserId"),
                "Unknown variable should remain as-is when failOnMissing=false");
    }

    @Test
    void stringInterpolationWithCustomResolver() {
        // Create a custom resolver that provides additional variables
        SeedVariableResolver customResolver = new SeedVariableResolver() {
            @Override
            public Optional<String> resolve(String variableName, SeedContext context) {
                if ("customVar".equals(variableName)) {
                    return Optional.of("custom-value-from-resolver");
                }
                return Optional.empty();
            }

            @Override
            public int priority() {
                return 10; // Higher than default context resolver
            }
        };

        // Build loader with custom resolver
        SeedLoader loader = SeedLoader.builder()
                .addSeedSource(new FileSeedSource("test", SEED_ROOT))
                .seedRepository(new MongoSeedRepository(mongoClient))
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .addVariableResolver(customResolver)
                .build();

        SeedContext context = SeedContext.builder(REALM)
                .tenantId("test-tenant")
                .build();

        // Apply the seed pack
        loader.apply(List.of(SeedPackRef.of("interpolation-seed")), context);

        // Verify standard interpolation still works
        MongoCollection<Document> rules = mongoClient.getDatabase(REALM).getCollection("securityRules");
        Document rule1 = rules.find(new Document("refName", "rule1")).first();
        assertNotNull(rule1);
        assertEquals("admin@test-tenant", rule1.getString("runAsUserId"));
    }

    @Test
    void stringInterpolationWithFailOnMissing() {
        // Create a manifest that has failOnMissing=true
        // For this test, we'll use the programmatic API to verify behavior

        SeedContext context = SeedContext.builder(REALM)
                .tenantId("tenant-123")
                .build();

        // Create transform directly with failOnMissing=true
        StringInterpolationTransform transform = new StringInterpolationTransform(
                null, // all fields
                true, // failOnMissing
                List.of(SeedContextVariableResolver.INSTANCE)
        );

        SeedPackManifest.Dataset dataset = new SeedPackManifest.Dataset();
        dataset.setCollection("test");

        // Test with a record containing an unknown variable
        java.util.Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("field", "{unknownVariable}");

        // Should throw exception when unknown variable encountered
        SeedLoadingException ex = assertThrows(SeedLoadingException.class, () -> {
            transform.apply(record, context, dataset);
        });

        assertTrue(ex.getMessage().contains("unknownVariable"),
                "Exception should mention the unknown variable");
    }

    @Test
    void stringInterpolationWithTargetFields() {
        // Test that specifying target fields only interpolates those fields

        SeedContext context = SeedContext.builder(REALM)
                .tenantId("tenant-123")
                .build();

        // Create transform that only targets "runAsUserId" field
        StringInterpolationTransform transform = new StringInterpolationTransform(
                java.util.Set.of("runAsUserId"), // only this field
                false,
                List.of(SeedContextVariableResolver.INSTANCE)
        );

        SeedPackManifest.Dataset dataset = new SeedPackManifest.Dataset();
        dataset.setCollection("test");

        java.util.Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("runAsUserId", "admin@{tenantId}");
        record.put("description", "Desc for {tenantId}"); // Should NOT be interpolated

        java.util.Map<String, Object> result = transform.apply(record, context, dataset);

        assertEquals("admin@tenant-123", result.get("runAsUserId"),
                "runAsUserId should be interpolated");
        assertEquals("Desc for {tenantId}", result.get("description"),
                "description should NOT be interpolated (not in target fields)");
    }

    @Test
    void customResolverOverridesContextResolver() {
        // Test that custom resolver with higher priority overrides context values

        SeedVariableResolver overrideResolver = new SeedVariableResolver() {
            @Override
            public Optional<String> resolve(String variableName, SeedContext context) {
                if ("tenantId".equals(variableName)) {
                    return Optional.of("overridden-tenant");
                }
                return Optional.empty();
            }

            @Override
            public int priority() {
                return 100; // Higher than context resolver (-100)
            }
        };

        SeedContext context = SeedContext.builder(REALM)
                .tenantId("original-tenant") // This should be overridden
                .build();

        StringInterpolationTransform transform = new StringInterpolationTransform(
                null,
                false,
                List.of(overrideResolver, SeedContextVariableResolver.INSTANCE)
        );

        SeedPackManifest.Dataset dataset = new SeedPackManifest.Dataset();
        dataset.setCollection("test");

        java.util.Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("field", "tenant={tenantId}");

        java.util.Map<String, Object> result = transform.apply(record, context, dataset);

        assertEquals("tenant=overridden-tenant", result.get("field"),
                "Custom resolver should override context value");
    }

    private Document findByRefName(List<Document> docs, String refName) {
        return docs.stream()
                .filter(d -> refName.equals(d.getString("refName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Document not found: " + refName));
    }
}
