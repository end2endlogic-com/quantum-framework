package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.EdgeChanges;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.mongo.OntologyWriteHook;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.service.OntologyReindexer;
import dev.morphia.MorphiaDatastore;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration test for ontology chain inference and reindex functionality.
 *
 * Test Scenario:
 * 1. Create entities: A -> B -> C (via annotated properties)
 * 2. Verify explicit edges: A->B, B->C are created
 * 3. Verify inferred edge: A->C is created via chain rule [refersToB, refersToC] => impliedRefersToC
 * 4. Out-of-band: Update B to reference D (B->D)
 * 5. Run reindex
 * 6. Verify new inferred edge: A->D is created via chain rule [refersToB, refersToD] => impliedRefersToD
 * 7. Verify APIs return correct edges and provenance
 */
@QuarkusTest
public class ChainInferenceAndReindexIT {

    private static final String REALM = "chain-inference-test";

    @Inject
    MorphiaDataStoreWrapper dataStoreWrapper;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    OntologyWriteHook writeHook;

    @Inject
    OntologyReindexer reindexer;

    @Inject
    OntologyRegistry registry;

    private DataDomain testDataDomain;
    private MorphiaDatastore datastore;

    @BeforeEach
    void setup() {
        // Get datastore for the specific realm (database)
        datastore = dataStoreWrapper.getDataStore(REALM);

        // Create test DataDomain for isolated realm
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("chain-test-org");
        testDataDomain.setAccountNum("9999999999");
        testDataDomain.setTenantId(REALM);
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);

        // Ensure a ResourceContext is active for MorphiaRepo operations
        SecurityContext.setResourceContext(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);

        // Clean all test collections for this realm
        edgeRepo.deleteAll(REALM);
        datastore.getDatabase().getCollection("chain_class_a").deleteMany(new Document());
        datastore.getDatabase().getCollection("chain_class_b").deleteMany(new Document());
        datastore.getDatabase().getCollection("chain_class_c").deleteMany(new Document());
        datastore.getDatabase().getCollection("chain_class_d").deleteMany(new Document());
    }

    @Test
    @DisplayName("Verify TBox contains chain rules for A->B->C and A->B->D")
    void verifyTBoxChainRules() {
        // Verify the TBox has our chain rules loaded
        var chains = registry.propertyChains();

        // Find our chain rules
        boolean hasABCChain = chains.stream()
                .anyMatch(c -> c.chain().equals(List.of("refersToB", "refersToC"))
                        && "impliedRefersToC".equals(c.implies()));
        boolean hasABDChain = chains.stream()
                .anyMatch(c -> c.chain().equals(List.of("refersToB", "refersToD"))
                        && "impliedRefersToD".equals(c.implies()));

        assertTrue(hasABCChain, "TBox should contain chain [refersToB, refersToC] => impliedRefersToC");
        assertTrue(hasABDChain, "TBox should contain chain [refersToB, refersToD] => impliedRefersToD");

        // Verify properties
        assertTrue(registry.propertyOf("refersToB").isPresent(), "refersToB property should exist");
        assertTrue(registry.propertyOf("refersToC").isPresent(), "refersToC property should exist");
        assertTrue(registry.propertyOf("refersToD").isPresent(), "refersToD property should exist");
        assertTrue(registry.propertyOf("impliedRefersToC").isPresent(), "impliedRefersToC property should exist");
        assertTrue(registry.propertyOf("impliedRefersToD").isPresent(), "impliedRefersToD property should exist");

        // Verify inferred properties are marked as such
        assertTrue(registry.propertyOf("impliedRefersToC").get().inferred(),
                "impliedRefersToC should be marked as inferred");
        assertTrue(registry.propertyOf("impliedRefersToD").get().inferred(),
                "impliedRefersToD should be marked as inferred");

        Log.info("TBox verified: chain rules and properties are correctly loaded");
    }

    @Test
    @DisplayName("Create A->B->C entities, run reindex, and verify chain inference creates A->C edge")
    void createEntitiesAndVerifyChainInference() throws Exception {
        // Create ClassC (terminal node)
        ChainClassC c = new ChainClassC();
        c.setRefName("CLASS-C-1");
        c.setDataDomain(testDataDomain);
        datastore.save(c);
        writeHook.afterPersist(REALM, c);
        Log.infof("Created ChainClassC id=%s", c.getId());

        // Create ClassB with reference to C
        ChainClassB b = new ChainClassB();
        b.setRefName("CLASS-B-1");
        b.setDataDomain(testDataDomain);
        b.setRefersToC(c);  // B -> C
        datastore.save(b);
        writeHook.afterPersist(REALM, b);
        Log.infof("Created ChainClassB id=%s with refersToC=%s", b.getId(), c.getId());

        // Verify B->C explicit edge exists
        List<OntologyEdge> bEdges = edgeRepo.findBySrc(testDataDomain, b.getId().toString());
        Log.infof("Edges from B: %s", bEdges.stream().map(e -> e.getP() + "->" + e.getDst()).toList());

        boolean hasBtoC = bEdges.stream()
                .anyMatch(e -> "refersToC".equals(e.getP()) && c.getId().toString().equals(e.getDst()));
        assertTrue(hasBtoC, "Explicit edge B->C (refersToC) should exist");

        // Create ClassA with reference to B
        ChainClassA a = new ChainClassA();
        a.setRefName("CLASS-A-1");
        a.setDataDomain(testDataDomain);
        a.setRefersToB(b);  // A -> B
        datastore.save(a);
        writeHook.afterPersist(REALM, a);
        Log.infof("Created ChainClassA id=%s with refersToB=%s", a.getId(), b.getId());

        // Verify A->B explicit edge exists
        List<OntologyEdge> aEdgesBeforeReindex = edgeRepo.findBySrc(testDataDomain, a.getId().toString());
        Log.infof("Edges from A before reindex: %s", aEdgesBeforeReindex.stream()
                .map(e -> (e.isInferred() ? "[INF]" : "[EXP]") + e.getP() + "->" + e.getDst())
                .toList());

        boolean hasAtoB = aEdgesBeforeReindex.stream()
                .anyMatch(e -> "refersToB".equals(e.getP()) && b.getId().toString().equals(e.getDst()) && !e.isInferred());
        assertTrue(hasAtoB, "Explicit edge A->B (refersToB) should exist");

        // Run reindex to trigger chain inference with proper YAML-backed registry
        Log.info("Running reindex to trigger chain inference...");
        reindexer.runAsync(REALM, true);
        waitUntilCompleted(30000);

        // Verify A->C inferred edge exists after reindex (via chain [refersToB, refersToC] => impliedRefersToC)
        List<OntologyEdge> aEdges = edgeRepo.findBySrc(testDataDomain, a.getId().toString());
        Log.infof("Edges from A after reindex: %s", aEdges.stream()
                .map(e -> (e.isInferred() ? "[INF]" : "[EXP]") + e.getP() + "->" + e.getDst())
                .toList());

        boolean hasAtoC = aEdges.stream()
                .anyMatch(e -> "impliedRefersToC".equals(e.getP())
                        && c.getId().toString().equals(e.getDst())
                        && e.isInferred());
        assertTrue(hasAtoC, "Inferred edge A->C (impliedRefersToC) should exist via chain inference after reindex");

        // Verify provenance on inferred edge
        OntologyEdge inferredAtoC = aEdges.stream()
                .filter(e -> "impliedRefersToC".equals(e.getP()) && e.isInferred())
                .findFirst()
                .orElse(null);
        assertNotNull(inferredAtoC, "Inferred edge should exist");
        assertNotNull(inferredAtoC.getProv(), "Provenance map should not be null");
        assertEquals("chain", inferredAtoC.getProv().get("rule"), "Provenance rule should be 'chain'");
        Log.infof("Inferred edge provenance: %s", inferredAtoC.getProv());
    }

    @Test
    @DisplayName("Out-of-band B->D update, then reindex discovers and creates A->D inferred edge")
    void outOfBandUpdateThenReindexDiscoversNewEdges() throws Exception {
        // === Phase 1: Create initial entities A->B->C ===
        ChainClassC c = new ChainClassC();
        c.setRefName("CLASS-C-1");
        c.setDataDomain(testDataDomain);
        datastore.save(c);
        writeHook.afterPersist(REALM, c);

        ChainClassB b = new ChainClassB();
        b.setRefName("CLASS-B-1");
        b.setDataDomain(testDataDomain);
        b.setRefersToC(c);
        datastore.save(b);
        writeHook.afterPersist(REALM, b);

        ChainClassA a = new ChainClassA();
        a.setRefName("CLASS-A-1");
        a.setDataDomain(testDataDomain);
        a.setRefersToB(b);
        datastore.save(a);
        writeHook.afterPersist(REALM, a);

        Log.info("Phase 1 complete: Created A->B->C chain");

        // Verify A has the expected edges
        List<OntologyEdge> aEdgesInitial = edgeRepo.findBySrc(testDataDomain, a.getId().toString());
        assertTrue(aEdgesInitial.stream().anyMatch(e -> "refersToB".equals(e.getP())), "A->B should exist");
        assertTrue(aEdgesInitial.stream().anyMatch(e -> "impliedRefersToC".equals(e.getP())), "A->C inferred should exist");

        // === Phase 2: Create D and update B->D out-of-band ===
        ChainClassD d = new ChainClassD();
        d.setRefName("CLASS-D-1");
        d.setDataDomain(testDataDomain);
        datastore.save(d);
        writeHook.afterPersist(REALM, d);
        Log.infof("Created ChainClassD id=%s", d.getId());

        // Reload B and update it out-of-band (without triggering write hook)
        ChainClassB bReloaded = datastore.find(ChainClassB.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", b.getId()))
                .first();
        assertNotNull(bReloaded, "Should be able to reload B");

        bReloaded.setRefersToD(d);
        datastore.save(bReloaded);
        // NOTE: Intentionally NOT calling writeHook.afterPersist here!
        Log.infof("Updated B out-of-band with refersToD=%s (write hook NOT called)", d.getId());

        // Verify B->D edge does NOT exist yet
        List<OntologyEdge> bEdgesBeforeReindex = edgeRepo.findBySrc(testDataDomain, b.getId().toString());
        boolean hasBtoD = bEdgesBeforeReindex.stream()
                .anyMatch(e -> "refersToD".equals(e.getP()) && d.getId().toString().equals(e.getDst()));
        assertFalse(hasBtoD, "B->D edge should NOT exist yet (out-of-band update)");

        // Verify A->D inferred edge does NOT exist yet
        List<OntologyEdge> aEdgesBeforeReindex = edgeRepo.findBySrc(testDataDomain, a.getId().toString());
        boolean hasAtoD = aEdgesBeforeReindex.stream()
                .anyMatch(e -> "impliedRefersToD".equals(e.getP()));
        assertFalse(hasAtoD, "A->D inferred edge should NOT exist yet");

        // === Phase 3: Run reindex ===
        Log.info("Phase 3: Running reindex...");
        reindexer.runAsync(REALM, true);
        waitUntilCompleted(30000);

        var result = reindexer.getResult();
        Log.infof("Reindex status: %s", result.status());
        Log.infof("Reindex summary: added=%d, modified=%d, removed=%d",
                result.summary().totalAdded(),
                result.summary().totalModified(),
                result.summary().totalRemoved());
        Log.infof("Added by predicate: %s", result.summary().addedByPredicate());
        Log.infof("Added by origin: %s", result.summary().addedByOrigin());

        assertThat(result.status(), startsWith("COMPLETED"));

        // === Phase 4: Verify B->D and A->D edges now exist ===
        List<OntologyEdge> bEdgesAfter = edgeRepo.findBySrc(testDataDomain, b.getId().toString());
        Log.infof("After reindex - B edges: %s",
                bEdgesAfter.stream().map(e -> (e.isInferred() ? "[INF]" : "[EXP]") + e.getP() + "->" + e.getDst()).toList());

        boolean hasBtoDAfter = bEdgesAfter.stream()
                .anyMatch(e -> "refersToD".equals(e.getP())
                        && d.getId().toString().equals(e.getDst())
                        && !e.isInferred());
        assertTrue(hasBtoDAfter, "After reindex: B->D explicit edge should exist");

        List<OntologyEdge> aEdgesAfter = edgeRepo.findBySrc(testDataDomain, a.getId().toString());
        Log.infof("After reindex - A edges: %s",
                aEdgesAfter.stream().map(e -> (e.isInferred() ? "[INF]" : "[EXP]") + e.getP() + "->" + e.getDst()).toList());

        boolean hasAtoDAfter = aEdgesAfter.stream()
                .anyMatch(e -> "impliedRefersToD".equals(e.getP())
                        && d.getId().toString().equals(e.getDst())
                        && e.isInferred());
        assertTrue(hasAtoDAfter, "After reindex: A->D inferred edge (impliedRefersToD) should exist");

        // Verify provenance on the newly inferred A->D edge
        OntologyEdge inferredAtoD = aEdgesAfter.stream()
                .filter(e -> "impliedRefersToD".equals(e.getP()) && e.isInferred())
                .findFirst()
                .orElse(null);
        assertNotNull(inferredAtoD, "A->D inferred edge should exist");
        assertNotNull(inferredAtoD.getProv(), "A->D edge should have provenance");
        assertEquals("chain", inferredAtoD.getProv().get("rule"), "Provenance rule should be 'chain'");
        Log.infof("A->D inferred edge provenance: %s", inferredAtoD.getProv());

        // Verify EdgeChanges contains the new edges
        EdgeChanges changes = result.changes();
        assertThat("Should have added edges", changes.added(), is(not(empty())));
    }

    @Test
    @DisplayName("Verify instance edges with provenance classification")
    void verifyInstanceEdgesWithProvenance() {
        // Create full chain A->B->C with D
        ChainClassC c = new ChainClassC();
        c.setRefName("CLASS-C-1");
        c.setDataDomain(testDataDomain);
        datastore.save(c);
        writeHook.afterPersist(REALM, c);

        ChainClassD d = new ChainClassD();
        d.setRefName("CLASS-D-1");
        d.setDataDomain(testDataDomain);
        datastore.save(d);
        writeHook.afterPersist(REALM, d);

        ChainClassB b = new ChainClassB();
        b.setRefName("CLASS-B-1");
        b.setDataDomain(testDataDomain);
        b.setRefersToC(c);
        b.setRefersToD(d);
        datastore.save(b);
        writeHook.afterPersist(REALM, b);

        ChainClassA a = new ChainClassA();
        a.setRefName("CLASS-A-1");
        a.setDataDomain(testDataDomain);
        a.setRefersToB(b);
        datastore.save(a);
        writeHook.afterPersist(REALM, a);

        // Get all edges for instance A
        List<OntologyEdge> aOutgoing = edgeRepo.findBySrc(testDataDomain, a.getId().toString());
        List<OntologyEdge> aIncoming = edgeRepo.findByDst(testDataDomain, a.getId().toString());

        Log.infof("Instance A outgoing edges: %d", aOutgoing.size());
        Log.infof("Instance A incoming edges: %d", aIncoming.size());

        // Verify we have both explicit and inferred edges
        Set<String> outgoingPredicates = aOutgoing.stream().map(OntologyEdge::getP).collect(Collectors.toSet());
        assertTrue(outgoingPredicates.contains("refersToB"), "Should have explicit refersToB edge");
        assertTrue(outgoingPredicates.contains("impliedRefersToC"), "Should have inferred impliedRefersToC edge");
        assertTrue(outgoingPredicates.contains("impliedRefersToD"), "Should have inferred impliedRefersToD edge");

        // Count by origin type
        long explicitCount = aOutgoing.stream().filter(e -> !e.isInferred() && !e.isDerived()).count();
        long inferredCount = aOutgoing.stream().filter(OntologyEdge::isInferred).count();

        Log.infof("Instance A: explicit=%d, inferred=%d", explicitCount, inferredCount);
        assertThat("Should have explicit edges", explicitCount, greaterThan(0L));
        assertThat("Should have inferred edges", inferredCount, greaterThan(0L));

        // Verify each inferred edge has provenance
        for (OntologyEdge e : aOutgoing) {
            if (e.isInferred()) {
                assertNotNull(e.getProv(), "Inferred edge " + e.getP() + " should have provenance");
                Log.infof("Inferred edge %s -> %s provenance: %s", e.getP(), e.getDst(), e.getProv());
            }
        }
    }

    @Test
    @DisplayName("Verify reindex is idempotent (no duplicate edges)")
    void verifyReindexIdempotency() throws Exception {
        // Create entities
        ChainClassC c = new ChainClassC();
        c.setRefName("CLASS-C-1");
        c.setDataDomain(testDataDomain);
        datastore.save(c);
        writeHook.afterPersist(REALM, c);

        ChainClassB b = new ChainClassB();
        b.setRefName("CLASS-B-1");
        b.setDataDomain(testDataDomain);
        b.setRefersToC(c);
        datastore.save(b);
        writeHook.afterPersist(REALM, b);

        ChainClassA a = new ChainClassA();
        a.setRefName("CLASS-A-1");
        a.setDataDomain(testDataDomain);
        a.setRefersToB(b);
        datastore.save(a);
        writeHook.afterPersist(REALM, a);

        // Run first reindex
        reindexer.runAsync(REALM, true);
        waitUntilCompleted(30000);

        // Get edge counts after first reindex
        List<OntologyEdge> aEdgesAfterFirst = edgeRepo.findBySrc(testDataDomain, a.getId().toString());
        int aCountAfterFirst = aEdgesAfterFirst.size();

        // Run second reindex
        reindexer.runAsync(REALM, false);
        waitUntilCompleted(30000);

        var result = reindexer.getResult();
        Log.infof("Second reindex - status: %s, added: %d, removed: %d",
                result.status(), result.summary().totalAdded(), result.summary().totalRemoved());

        // Verify idempotency - counts should be the same
        List<OntologyEdge> aEdgesAfterSecond = edgeRepo.findBySrc(testDataDomain, a.getId().toString());
        assertEquals(aCountAfterFirst, aEdgesAfterSecond.size(),
                "Edge count should be unchanged after second reindex");

        // Verify no duplicate edges (each predicate+dst combination should appear only once)
        Map<String, Long> predicateDstCounts = aEdgesAfterSecond.stream()
                .collect(Collectors.groupingBy(e -> e.getP() + "|" + e.getDst(), Collectors.counting()));
        for (var entry : predicateDstCounts.entrySet()) {
            assertEquals(1L, entry.getValue().longValue(),
                    "Should have no duplicate edges: " + entry.getKey());
        }
    }

    private void waitUntilCompleted(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = reindexer.status();
            if (!reindexer.isRunning() && (status.startsWith("COMPLETED") || status.startsWith("FAILED"))) {
                if (status.startsWith("FAILED")) {
                    fail("Reindex failed: " + status);
                }
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Reindexer did not complete in time; status=" + reindexer.status());
    }
}
