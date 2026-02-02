package com.e2eq.framework.service.seed;

import static org.junit.jupiter.api.Assertions.*;

import com.e2eq.framework.test.MongoDbInitResource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClasspathSeedSource} to verify seed pack discovery and dataset loading
 * from classpath resources. This is important for production deployments where seed data
 * is packaged inside JARs.
 */
@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
class ClasspathSeedSourceTest {

    private static final String REALM = "classpath-seed-source-test";

    @Inject
    MongoClient mongoClient;

    private ClasspathSeedSource classpathSource;

    @BeforeEach
    void setUp() {
        mongoClient.getDatabase(REALM).drop();
        classpathSource = new ClasspathSeedSource(
                Thread.currentThread().getContextClassLoader(),
                "test-classpath"
        );
    }

    @Test
    void discoversSeedPacksFromClasspath() throws IOException {
        SeedContext context = SeedContext.builder(REALM).build();

        List<SeedPackDescriptor> packs = classpathSource.loadSeedPacks(context);

        assertFalse(packs.isEmpty(), "Should discover at least one seed pack from classpath");

        // Verify demo-seed is found (from test/resources/seed-packs)
        SeedPackDescriptor demoPack = packs.stream()
                .filter(p -> "demo-seed".equals(p.getManifest().getSeedPack()))
                .findFirst()
                .orElse(null);

        assertNotNull(demoPack, "Should find demo-seed pack on classpath");
        assertEquals("1.0.0", demoPack.getManifest().getVersion());
        assertNotNull(demoPack.getManifestUrl(), "Manifest URL should be set for classpath resolution");
    }

    @Test
    void opensDatasetFromClasspath() throws IOException {
        SeedContext context = SeedContext.builder(REALM).build();

        List<SeedPackDescriptor> packs = classpathSource.loadSeedPacks(context);
        SeedPackDescriptor demoPack = packs.stream()
                .filter(p -> "demo-seed".equals(p.getManifest().getSeedPack()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("demo-seed not found"));

        // This tests the fix for the "Stream closed" bug - the stream should be readable
        try (InputStream in = classpathSource.openDataset(demoPack, "datasets/codeLists.ndjson")) {
            assertNotNull(in, "Dataset stream should not be null");

            // Read the entire content - this would fail with "Stream closed" before the fix
            byte[] content = in.readAllBytes();
            assertNotNull(content, "Should be able to read content");
            assertTrue(content.length > 0, "Content should not be empty");

            // Verify it's valid NDJSON content
            String text = new String(content, StandardCharsets.UTF_8);
            assertTrue(text.contains("codeListName"), "Should contain expected field");
        }
    }

    @Test
    void loadsAndAppliesSeedsFromClasspath() throws IOException {
        SeedContext context = SeedContext.builder(REALM)
                .tenantId("cp-tenant")
                .orgRefName("cp-org")
                .accountId("cp-acct")
                .ownerId("cp-owner")
                .build();

        SeedLoader loader = SeedLoader.builder()
                .addSeedSource(classpathSource)
                .seedRepository(new MongoSeedRepository(mongoClient))
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .build();

        // Apply the demo-seed pack discovered from classpath
        loader.apply(List.of(SeedPackRef.of("demo-seed")), context);

        // Verify data was loaded
        MongoCollection<Document> collection = mongoClient.getDatabase(REALM).getCollection("codeLists-demo");
        List<Document> documents = collection.find().sort(Sorts.ascending("code")).into(new ArrayList<>());

        assertEquals(2, documents.size(), "Should have loaded 2 records");

        // Verify tenant substitution was applied
        Document firstDoc = documents.get(0);
        Document dataDomain = (Document) firstDoc.get("dataDomain");
        assertNotNull(dataDomain, "DataDomain should be set");
        assertEquals("cp-tenant", dataDomain.getString("tenantId"));
        assertEquals("cp-org", dataDomain.getString("orgRefName"));
        assertEquals("cp-acct", dataDomain.getString("accountNum"));
        assertEquals("cp-owner", dataDomain.getString("ownerId"));
    }

    @Test
    void handlesMultipleReadsFromSameDataset() throws IOException {
        SeedContext context = SeedContext.builder(REALM).build();

        List<SeedPackDescriptor> packs = classpathSource.loadSeedPacks(context);
        SeedPackDescriptor demoPack = packs.stream()
                .filter(p -> "demo-seed".equals(p.getManifest().getSeedPack()))
                .findFirst()
                .orElseThrow();

        // Read multiple times to ensure the source can be re-opened
        for (int i = 0; i < 3; i++) {
            try (InputStream in = classpathSource.openDataset(demoPack, "datasets/codeLists.ndjson")) {
                byte[] content = in.readAllBytes();
                assertTrue(content.length > 0, "Read #" + (i + 1) + " should succeed");
            }
        }
    }

    @Test
    void throwsOnMissingDataset() throws IOException {
        SeedContext context = SeedContext.builder(REALM).build();

        List<SeedPackDescriptor> packs = classpathSource.loadSeedPacks(context);
        SeedPackDescriptor demoPack = packs.stream()
                .filter(p -> "demo-seed".equals(p.getManifest().getSeedPack()))
                .findFirst()
                .orElseThrow();

        assertThrows(IOException.class, () -> {
            classpathSource.openDataset(demoPack, "datasets/nonexistent.json");
        }, "Should throw IOException for missing dataset");
    }
}
