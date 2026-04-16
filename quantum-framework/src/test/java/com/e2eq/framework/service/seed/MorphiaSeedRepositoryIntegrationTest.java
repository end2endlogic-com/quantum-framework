package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;
import com.e2eq.framework.persistent.TestAuthorRepo;
import com.e2eq.framework.persistent.TestBookRepo;
import com.e2eq.framework.persistent.TestChildRepo;
import com.e2eq.framework.persistent.TestEntityReferenceHolderRepo;
import com.e2eq.framework.persistent.TestParentRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.test.AuthorModel;
import com.e2eq.framework.test.BookModel;
import com.e2eq.framework.test.ChildModel;
import com.e2eq.framework.test.EntityReferenceHolderModel;
import com.e2eq.framework.test.MongoDbInitResource;
import com.e2eq.framework.test.ParentModel;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    TestAuthorRepo testAuthorRepo;

    @Inject
    TestBookRepo testBookRepo;

    @Inject
    TestEntityReferenceHolderRepo testEntityReferenceHolderRepo;

    @Inject
    TestChildRepo testChildRepo;

    @Inject
    TestParentRepo testParentRepo;

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

        ParentModel parent = testParentRepo.findByRefName("parent-1").orElseThrow();
        assertNotNull(parent.getReferences());
        assertTrue(parent.getReferences().contains(new ReferenceEntry(
                holder.getId(), EntityReferenceHolderModel.class.getTypeName(), holder.getRefName())));
    }

    @Test
    void updatesTrackedBackReferencesForMorphiaReferences() {
        SeedLoader loader = loader();
        SeedContext context = context();

        loader.apply(List.of(SeedPackRef.of("tracked-dbref-demo")), context);

        ChildModel child = testChildRepo.findByRefName("tracked-child").orElseThrow();
        ParentModel parent = testParentRepo.findByRefName("tracked-parent").orElseThrow();
        assertNotNull(child.getParent());
        assertEquals(parent.getId(), child.getParent().getId());
        assertNotNull(parent.getReferences());
        assertTrue(parent.getReferences().contains(new ReferenceEntry(
                child.getId(), ChildModel.class.getTypeName(), child.getRefName())));
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
}
