package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.general.MenuHierarchyModel;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MenuHierarchyRepo;
import com.e2eq.framework.persistent.TestAuthorRepo;
import com.e2eq.framework.persistent.TestBookRepo;
import com.e2eq.framework.persistent.TestEntityReferenceHolderRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.test.AuthorModel;
import com.e2eq.framework.test.BookModel;
import com.e2eq.framework.test.EntityReferenceHolderModel;
import com.e2eq.framework.test.MongoDbInitResource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
class MorphiaSeedRepositoryIntegrationTest {

    private static final Path SEED_ROOT = Path.of("src", "test", "resources", "seed-packs");
    private static final String REALM = "morphia-seed-it";

    @Inject
    MongoClient mongoClient;

    @Inject
    MorphiaSeedRepository morphiaSeedRepository;

    @Inject
    MenuHierarchyRepo menuHierarchyRepo;

    @Inject
    TestAuthorRepo testAuthorRepo;

    @Inject
    TestBookRepo testBookRepo;

    @Inject
    TestEntityReferenceHolderRepo testEntityReferenceHolderRepo;

    @BeforeEach
    void setup() {
        // Clean DB
        mongoClient.getDatabase(REALM).drop();
        // Establish a minimal security context so Morphia repos can resolve filters and data domain
        DataDomain dd = new DataDomain("org-morphia", "0000000042", "tenant.morphia", 0, "owner-xyz");
        PrincipalContext pc = new PrincipalContext.Builder()
                .withDefaultRealm(REALM)
                .withDataDomain(dd)
                .withUserId("admin@tenant.morphia")
                .withRoles(new String[]{"ADMIN"})
                .withScope("it")
                .build();
        ResourceContext rc = new ResourceContext.Builder()
                .withRealm(REALM)
                .withArea("INTEGRATION")
                .withFunctionalDomain("CODE_LISTS")
                .withAction("SAVE")
                .build();
        SecurityContext.setPrincipalContext(pc);
        SecurityContext.setResourceContext(rc);
    }

    @AfterEach
    void cleanUpThreads() {
       SecurityContext.clear();
    }

    @Test
    void appliesSeedPackViaMorphiaRepo() {
        SeedLoader loader = SeedLoader.builder()
                .addSeedSource(new FileSeedSource("test", SEED_ROOT))
                .seedRepository(morphiaSeedRepository)
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .build();

        SeedContext context = SeedContext.builder(REALM)
                .tenantId("tenant.morphia")
                .orgRefName("org-morphia")
                .accountId("0000000042")
                .ownerId("owner-xyz")
                .build();

        loader.apply(List.of(SeedPackRef.of("morphia-demo")), context);

        // CodeList default collection name from Morphia is typically the class name; try common variants
        long total = 0;
        total += mongoClient.getDatabase(REALM).getCollection("CodeList").countDocuments();
        total += mongoClient.getDatabase(REALM).getCollection("codeList").countDocuments();
        assertEquals(2L, total, "Expected 2 CodeList documents inserted via Morphia");

        // Try to fetch by key from whichever collection exists
        MongoCollection<Document> col = mongoClient.getDatabase(REALM)
                .getCollection(exists(REALM, "CodeList") ? "CodeList" : "codeList");
        List<Document> docs = col.find().sort(Sorts.ascending("key")).into(new ArrayList<>());
        assertEquals(2, docs.size());

        Document colors = docs.stream().filter(d -> "COLORS".equals(d.getString("category"))).findFirst().orElseThrow();
        assertEquals("RED", colors.getString("key"));
        // Ensure Morphia discriminator or unmapped fields are present (best-effort check)
        assertNotNull(colors);

        // Verify seed registry captured application
        Document registry = mongoClient.getDatabase(REALM)
                .getCollection("_seed_registry")
                .find(Filters.and(
                        Filters.eq("seedPack", "morphia-demo"),
                        Filters.eq("version", "1.0.0"),
                        Filters.eq("dataset", "CodeList")))
                .first();
        assertNotNull(registry, "Registry entry should exist for Morphia dataset");
        assertEquals(2, registry.getInteger("records"));
    }

    @Test
    void upsertMatchesExistingSeededRecordByIdWhenNaturalKeyDrifts() {
        SecurityContext.setResourceContext(new ResourceContext.Builder()
                .withRealm(REALM)
                .withArea("SYSTEM")
                .withFunctionalDomain("MENU")
                .withAction("SAVE")
                .build());

        ObjectId seedId = new ObjectId("100000000000000000000004");

        MenuHierarchyModel existing = new MenuHierarchyModel();
        existing.setId(seedId);
        existing.setRefName("legacy-bootstrap-defaults-node");
        existing.setDisplayName("Legacy Bootstrap Defaults");
        menuHierarchyRepo.save(REALM, existing);

        SeedPackManifest.Dataset dataset = new SeedPackManifest.Dataset();
        dataset.setModelClass(MenuHierarchyModel.class.getName());
        dataset.setRepoClass(MenuHierarchyRepo.class.getName());
        dataset.setNaturalKey(List.of("refName"));
        dataset.setUpsert(true);

        SeedContext context = context();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", seedId.toHexString());
        record.put("refName", "di-system-bootstrap-defaults-node");
        record.put("displayName", "BOOTSTRAP DEFAULTS");

        assertDoesNotThrow(() -> morphiaSeedRepository.upsertRecord(context, dataset, record));

        MenuHierarchyModel updated = menuHierarchyRepo.findById(seedId, REALM).orElseThrow();
        assertEquals("di-system-bootstrap-defaults-node", updated.getRefName());
        assertEquals("BOOTSTRAP DEFAULTS", updated.getDisplayName());
        assertTrue(menuHierarchyRepo.findByRefName("legacy-bootstrap-defaults-node", REALM).isEmpty());
        assertEquals(1L, countMenuHierarchyDocuments(), "Expected a single menu hierarchy record after id-based upsert");
    }

    @Test
    void resolvesMorphiaReferencesByRefNameAfterReorderingDatasets() {
        SeedLoader loader = loader();
        SeedContext context = context();

        loader.apply(List.of(SeedPackRef.of("reference-demo")), context);

        BookModel book = testBookRepo.findByRefName("foundation").orElseThrow();
        assertNotNull(book.getAuthor(), "Expected author reference to be materialized");
        assertEquals("isaac-asimov", book.getAuthor().getRefName());
        assertNotNull(book.getAuthor().getId());
    }

    @Test
    void resolvesMorphiaReferencesAgainstExistingDatabaseRecords() {
        AuthorModel author = new AuthorModel();
        author.setRefName("existing-author");
        author.setDisplayName("Existing Author");
        author.setAuthorName("Existing Author");
        author = testAuthorRepo.save(author);

        SeedLoader loader = loader();
        loader.apply(List.of(SeedPackRef.of("reference-existing-author")), context());

        BookModel book = testBookRepo.findByRefName("robots").orElseThrow();
        assertNotNull(book.getAuthor());
        assertEquals(author.getId(), book.getAuthor().getId());
        assertEquals("existing-author", book.getAuthor().getRefName());
    }

    @Test
    void failsClosedWhenMorphiaReferenceCannotBeResolved() {
        SeedLoader loader = loader();
        SeedContext context = context();

        SeedLoadingException exception = assertThrows(SeedLoadingException.class,
                () -> loader.apply(List.of(SeedPackRef.of("reference-missing-author")), context));
        assertTrue(exception.getMessage().contains("Unable to resolve reference field 'author'"));
    }

    @Test
    void resolvesEntityReferencesByRefNameAfterReorderingDatasets() {
        SeedLoader loader = loader();
        SeedContext context = context();

        loader.apply(List.of(SeedPackRef.of("entity-reference-demo")), context);

        EntityReferenceHolderModel holder = testEntityReferenceHolderRepo.findByRefName("holder-1").orElseThrow();
        assertNotNull(holder.getLinkedParent());
        assertNotNull(holder.getLinkedParent().getEntityId());
        assertEquals("parent-1", holder.getLinkedParent().getEntityRefName());
    }

    private SeedLoader loader() {
        return SeedLoader.builder()
                .addSeedSource(new FileSeedSource("test", SEED_ROOT))
                .seedRepository(morphiaSeedRepository)
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .build();
    }

    private SeedContext context() {
        return SeedContext.builder(REALM)
                .tenantId("tenant.morphia")
                .orgRefName("org-morphia")
                .accountId("0000000042")
                .ownerId("owner-xyz")
                .build();
    }

    private boolean exists(String realm, String collection) {
        for (String name : mongoClient.getDatabase(realm).listCollectionNames()) {
            if (name.equals(collection)) return true;
        }
        return false;
    }

    private long countMenuHierarchyDocuments() {
        long total = 0L;
        for (String name : mongoClient.getDatabase(REALM).listCollectionNames()) {
            if (name.toLowerCase().contains("menuhierarchy")) {
                total += mongoClient.getDatabase(REALM).getCollection(name).countDocuments();
            }
        }
        return total;
    }
}
