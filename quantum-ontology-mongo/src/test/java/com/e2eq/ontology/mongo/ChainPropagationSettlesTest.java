package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.ontology.core.EdgeChanges;
import com.e2eq.ontology.core.EdgeRecord;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chain propagation reaches a fixpoint on a graph without recursive chains: once nothing in the store
 * changes any more it stops, well inside its re-derivation budget.
 *
 * <p>The ontology is the common "child of a parent, parent of an owner" shape where every property has
 * an inverse: an intake belongs to a declaration, the declaration is filed by a broker and belongs to
 * a project, and two chains lift the broker and the project onto the intake.</p>
 */
@QuarkusTest
public class ChainPropagationSettlesTest {

    private static final int BUDGET = 40;

    @Inject
    TenantOntologyTBoxRepo tboxRepo;

    @Inject
    TenantOntologyRegistryProvider provider;

    @Inject
    OntologyMaterializer materializer;

    @Inject
    OntologyEdgeRepo edgeRepo;

    private ChainPropagator propagator;
    private DataDomain tenant;
    private SecurityCallScope.Scope ignoredRulesScope;

    @BeforeEach
    void setup() {
        ignoredRulesScope = SecurityCallScope.openIgnoringRules();
        tboxRepo.deleteAll();
        edgeRepo.deleteAll();
        provider.clearCache();
        tenant = new DataDomain("org1", "acc1", "chain-settles-tenant", 0, "user1");
        TenantOntologyTBox doc = new TenantOntologyTBox(intakeTBox(), "settles-hash", "settles-yaml", "test", "1.0.0");
        doc.setDataDomain(tenant);
        doc.setRefName("tbox-chain-settles");
        doc.setDisplayName("Chain propagation settles");
        tboxRepo.save(doc);
        propagator = new ChainPropagator(materializer, edgeRepo, provider, BUDGET);
    }

    @AfterEach
    void cleanup() {
        provider.clearCache();
        if (ignoredRulesScope != null) {
            ignoredRulesScope.close();
        }
    }

    @Test
    void savingAnIntakeSettles() {
        seed();

        assertSettled(write("intake1", "Intake", edge("intake1", "Intake", "intakeForDeclaration", "decl1", "Declaration")));

        assertEquals(Set.of("broker1"), dsts("intake1", "intakeForBroker"));
        assertEquals(Set.of("project1"), dsts("intake1", "intakeForProject"));
        assertEquals(Set.of("intake1", "intake2"), dsts("broker1", "brokerHasIntake"));
        assertEquals(Set.of("intake1", "intake2"), dsts("project1", "projectHasIntake"));
    }

    @Test
    void reSavingTheDeclarationSettles() {
        seed();

        assertSettled(write("decl1", "Declaration", edge("decl1", "Declaration", "filedByBroker", "broker1", "Broker"),
                edge("decl1", "Declaration", "belongsToProject", "project1", "Project")));

        assertEquals(Set.of("broker1"), dsts("intake1", "intakeForBroker"));
        assertEquals(Set.of("intake1", "intake2"), dsts("decl1", "declarationHasIntake"));
    }

    @Test
    void movingTheDeclarationToAnotherBrokerSettlesAndFollows() {
        seed();

        assertSettled(write("decl1", "Declaration", edge("decl1", "Declaration", "filedByBroker", "broker2", "Broker"),
                edge("decl1", "Declaration", "belongsToProject", "project1", "Project")));

        assertEquals(Set.of("broker2"), dsts("intake1", "intakeForBroker"));
        assertEquals(Set.of("broker2"), dsts("intake2", "intakeForBroker"));
        assertEquals(Set.of(), dsts("broker1", "brokerHasIntake"));
        assertEquals(Set.of("intake1", "intake2"), dsts("broker2", "brokerHasIntake"));
    }

    @Test
    void anIntakeThatAlsoAssertsItsBrokerSettles() {
        seed();

        assertSettled(write("intake1", "Intake", edge("intake1", "Intake", "intakeForDeclaration", "decl1", "Declaration"),
                edge("intake1", "Intake", "intakeForBroker", "broker1", "Broker"),
                edge("intake1", "Intake", "intakeForProject", "project1", "Project")));
        assertSettled(write("intake1", "Intake", edge("intake1", "Intake", "intakeForDeclaration", "decl1", "Declaration"),
                edge("intake1", "Intake", "intakeForBroker", "broker1", "Broker"),
                edge("intake1", "Intake", "intakeForProject", "project1", "Project")));

        assertEquals(Set.of("intake1", "intake2"), dsts("broker1", "brokerHasIntake"));
    }

    /** Re-deriving a node whose stored edges are already right reports nothing and writes nothing. */
    @Test
    void aSettledGraphReDerivesToNoChange() {
        seed();
        Set<String> before = edgeKeys();

        for (Map.Entry<String, String> node : NODES.entrySet()) {
            assertNoChange(node.getKey(), materializer.rederive(null, tenant, node.getKey(), node.getValue()));
        }

        assertEquals(before, edgeKeys());
    }

    /**
     * Releases before the ownership rule let an inverse image overwrite the other end's assertion,
     * leaving both ends of a relation as inferred images of each other. Re-deriving either end must
     * leave such a pair alone: neither end can tell which side asserted it.
     */
    @Test
    void aPairOfImagesWithNoAssertionIsLeftAloneByBothEnds() {
        seed();
        overwriteAssertionsWithImages();
        Set<String> before = edgeKeys();

        for (Map.Entry<String, String> node : NODES.entrySet()) {
            assertNoChange(node.getKey(), materializer.rederive(null, tenant, node.getKey(), node.getValue()));
        }

        assertEquals(before, edgeKeys());
    }

    @Test
    void propagationOverPairsOfImagesSettles() {
        seed();
        overwriteAssertionsWithImages();
        Set<String> before = edgeKeys();

        assertSettled(propagator.propagate(null, tenant, "broker1", Set.of("brokerFilesDeclaration", "brokerHasIntake")));
        assertSettled(propagator.propagate(null, tenant, "decl1",
                Set.of("filedByBroker", "belongsToProject", "declarationHasIntake", "declarationHasFinding")));
        assertSettled(write("finding2", "Finding", edge("finding2", "Finding", "findingForDeclaration", "decl1", "Declaration")));

        Set<String> after = edgeKeys();
        after.removeIf(k -> k.contains("finding2"));
        assertEquals(before, after);
        assertEquals(Set.of("broker1"), dsts("finding2", "findingForBroker"));
    }

    /** Saving the entity that asserts the edge makes the edge its own again and keeps the image. */
    @Test
    void reSavingTheAssertingEntityTakesThePairBack() {
        seed();
        overwriteAssertionsWithImages();

        assertSettled(write("decl1", "Declaration", edge("decl1", "Declaration", "filedByBroker", "broker1", "Broker"),
                edge("decl1", "Declaration", "belongsToProject", "project1", "Project")));

        assertEquals(Set.of(false), inferredFlags("decl1", "filedByBroker", "broker1"));
        assertEquals(Set.of(true), inferredFlags("broker1", "brokerFilesDeclaration", "decl1"));

        assertSettled(write("decl1", "Declaration", edge("decl1", "Declaration", "belongsToProject", "project1", "Project")));

        assertEquals(Set.of(), dsts("decl1", "filedByBroker"));
        assertEquals(Set.of(), dsts("broker1", "brokerFilesDeclaration"), "the image outlived the assertion it mirrors");
    }

    /**
     * An entity's own image, read back from a neighbour, is not a reason for the edge it mirrors. The
     * intake still reaches the broker over a property no rule reads, so its snapshot holds the
     * broker's {@code brokerHasIntake} image; once the chain's first hop is gone that image must not
     * keep {@code intakeForBroker} alive, nor the edge keep the image.
     */
    @Test
    void anImageReadBackFromANeighbourDoesNotKeepItsEdgeAlive() {
        assertSettled(write("decl1", "Declaration", edge("decl1", "Declaration", "filedByBroker", "broker1", "Broker")));
        assertSettled(write("intake1", "Intake", edge("intake1", "Intake", "intakeForDeclaration", "decl1", "Declaration"),
                edge("intake1", "Intake", "intakeSeenByBroker", "broker1", "Broker")));
        assertEquals(Set.of("broker1"), dsts("intake1", "intakeForBroker"));
        assertEquals(Set.of("intake1"), dsts("broker1", "brokerHasIntake"));

        assertSettled(write("intake1", "Intake", edge("intake1", "Intake", "intakeSeenByBroker", "broker1", "Broker")));

        assertEquals(Set.of(), dsts("intake1", "intakeForBroker"), "the chain's first hop is gone");
        assertEquals(Set.of(), dsts("broker1", "brokerHasIntake"), "the image outlived the edge it mirrors");
    }

    /** A chain edge keeps its chain derivation when its own image is in the snapshot. */
    @Test
    void aChainEdgeIsNotRewrittenAsTheInverseOfItsOwnImage() {
        assertSettled(write("decl1", "Declaration", edge("decl1", "Declaration", "filedByBroker", "broker1", "Broker")));
        Reasoner.Edge[] edges = {edge("intake1", "Intake", "intakeForDeclaration", "decl1", "Declaration"),
                edge("intake1", "Intake", "intakeSeenByBroker", "broker1", "Broker")};
        assertSettled(write("intake1", "Intake", edges));
        assertSettled(write("intake1", "Intake", edges));

        assertEquals(Set.of("chain"), edgeRepo.findBySrc((String) null, tenant, "intake1").stream()
                .filter(e -> "intakeForBroker".equals(e.getP()))
                .map(e -> String.valueOf(e.getProv().get("rule")))
                .collect(Collectors.toSet()));
    }

    /**
     * Only the edge an image mirrors is refused as that image's consequence. A different edge the
     * image implies is still derived: {@code partnerOf} is symmetric and the inverse of
     * {@code partneredBy}, so the symmetric image at the other broker implies {@code partneredBy} here.
     */
    @Test
    void anotherEdgeImpliedByAnOwnImageIsStillDerived() {
        Reasoner.Edge partner = edge("broker1", "Broker", "partnerOf", "broker2", "Broker");
        assertSettled(write("broker1", "Broker", partner));
        assertSettled(write("broker1", "Broker", partner));
        assertSettled(write("broker1", "Broker", partner));

        assertEquals(Set.of("broker2"), dsts("broker1", "partneredBy"));
        assertEquals(Set.of(false), inferredFlags("broker1", "partnerOf", "broker2"));
    }

    /**
     * An edge derived from an own image goes with the edge that image mirrors. {@code affiliateOf} is
     * implied by a chain, symmetric, and the inverse of {@code affiliatedBy}: its symmetric image at
     * the other broker implies {@code affiliatedBy} here. When the chain breaks, the stored image is
     * still in the snapshot on the pass that removes it, and must not leave that edge behind.
     */
    @Test
    void anEdgeImpliedByAnOwnImageGoesWithTheEdgeTheImageMirrors() {
        Reasoner.Edge ally = edge("broker2", "Broker", "allyOf", "broker3", "Broker");
        Reasoner.Edge[] own = {edge("broker1", "Broker", "agentOf", "broker2", "Broker"),
                edge("broker1", "Broker", "knows", "broker3", "Broker")};
        assertSettled(write("broker2", "Broker", ally));
        assertSettled(write("broker1", "Broker", own));
        assertSettled(write("broker1", "Broker", own));
        assertEquals(Set.of("broker3"), dsts("broker1", "affiliateOf"));
        assertEquals(Set.of("broker3"), dsts("broker1", "affiliatedBy"));

        assertSettled(write("broker2", "Broker"));

        assertEquals(Set.of(), dsts("broker1", "affiliateOf"), "the chain's second hop is gone");
        assertEquals(Set.of(), dsts("broker3", "affiliateOf"));
        assertEquals(Set.of(), dsts("broker1", "affiliatedBy"), "affiliatedBy outlived the affiliateOf it came from");
    }

    /**
     * An edge kept because the edge behind its image still stands is in turn a reason for the next
     * one: {@code tieOf} is symmetric with inverse {@code tiedBy}, itself symmetric with inverse
     * {@code tieBack}, so each is reached through the image of the one before.
     */
    @Test
    void edgesImpliedThroughSuccessiveOwnImagesAreAllDerived() {
        Reasoner.Edge tie = edge("broker1", "Broker", "tieOf", "broker2", "Broker");
        for (int i = 0; i < 5; i++) {
            assertSettled(write("broker1", "Broker", tie));
        }
        assertEquals(Set.of("broker2"), dsts("broker1", "tiedBy"));
        assertEquals(Set.of("broker2"), dsts("broker1", "tieBack"));

        assertSettled(write("broker1", "Broker", tie));
        assertEquals(Set.of("broker2"), dsts("broker1", "tieBack"), "tieBack was dropped while tieOf still stands");
    }

    private static final Map<String, String> NODES = Map.of("decl1", "Declaration", "intake1", "Intake",
            "intake2", "Intake", "finding1", "Finding", "broker1", "Broker", "project1", "Project");

    /** Store every asserted edge of the seed as an inferred image of its own inverse. */
    private void overwriteAssertionsWithImages() {
        overwrite("decl1", "Declaration", "filedByBroker", "broker1", "Broker", "brokerFilesDeclaration");
        overwrite("decl1", "Declaration", "belongsToProject", "project1", "Project", "projectIncludesDeclaration");
        overwrite("intake1", "Intake", "intakeForDeclaration", "decl1", "Declaration", "declarationHasIntake");
        overwrite("intake2", "Intake", "intakeForDeclaration", "decl1", "Declaration", "declarationHasIntake");
        overwrite("finding1", "Finding", "findingForDeclaration", "decl1", "Declaration", "declarationHasFinding");
    }

    private void overwrite(String src, String srcType, String p, String dst, String dstType, String inverse) {
        Map<String, Object> mirrored = Map.of("src", dst, "p", inverse, "dst", src, "srcType", dstType, "dstType", srcType);
        Map<String, Object> prov = Map.of("rule", "inverse", "inputs", Map.of("of", inverse, "inputs", List.of(mirrored)));
        edgeRepo.bulkUpsertEdgeRecords((String) null, List.of(new EdgeRecord(DataDomainConverter.toInfo(tenant),
                srcType, src, p, dstType, dst, true, prov, new Date())));
        assertEquals(Set.of(true), inferredFlags(src, p, dst), "the test could not store the edge as inferred");
    }

    private static void assertNoChange(String node, EdgeChanges changes) {
        assertTrue(changes.added().isEmpty() && changes.modified().isEmpty() && changes.removed().isEmpty(),
                "re-deriving " + node + " changed added=" + keys(changes.added()) + " modified=" + keys(changes.modified())
                        + " removed=" + keys(changes.removed()));
    }

    private static List<String> keys(List<EdgeRecord> records) {
        return records.stream().map(r -> r.getSrc() + " " + r.getP() + " " + r.getDst()).toList();
    }

    private Set<String> edgeKeys() {
        Set<String> keys = new java.util.TreeSet<>();
        for (String node : List.of("decl1", "intake1", "intake2", "finding1", "finding2", "broker1", "project1")) {
            edgeRepo.findBySrc((String) null, tenant, node)
                    .forEach(e -> keys.add(e.getSrc() + " " + e.getP() + " " + e.getDst()));
        }
        return keys;
    }

    private Set<Boolean> inferredFlags(String src, String p, String dst) {
        return edgeRepo.findBySrc((String) null, tenant, src).stream()
                .filter(e -> p.equals(e.getP()) && dst.equals(e.getDst()))
                .map(e -> e.isInferred())
                .collect(Collectors.toSet());
    }

    private void seed() {
        assertSettled(write("decl1", "Declaration", edge("decl1", "Declaration", "filedByBroker", "broker1", "Broker"),
                edge("decl1", "Declaration", "belongsToProject", "project1", "Project")));
        assertSettled(write("intake1", "Intake", edge("intake1", "Intake", "intakeForDeclaration", "decl1", "Declaration")));
        assertSettled(write("intake2", "Intake", edge("intake2", "Intake", "intakeForDeclaration", "decl1", "Declaration")));
        assertSettled(write("finding1", "Finding", edge("finding1", "Finding", "findingForDeclaration", "decl1", "Declaration")));
    }

    private static void assertSettled(ChainPropagator.Result result) {
        assertFalse(result.truncated(), "propagation ran out of its budget of " + BUDGET + " re-derivations: " + result);
        assertTrue(result.pendingHeads().isEmpty(), "heads left unrepaired: " + result.pendingHeads());
    }

    private ChainPropagator.Result write(String id, String type, Reasoner.Edge... edges) {
        EdgeChanges changes = materializer.apply(tenant, id, type, List.of(edges));
        return propagator.propagate(null, tenant, id, changes);
    }

    private Set<String> dsts(String src, String p) {
        return edgeRepo.findBySrc((String) null, tenant, src).stream()
                .filter(e -> p.equals(e.getP()))
                .map(e -> e.getDst())
                .collect(Collectors.toSet());
    }

    private static Reasoner.Edge edge(String src, String srcType, String p, String dst, String dstType) {
        return new Reasoner.Edge(src, srcType, p, dst, dstType, false, Optional.empty());
    }

    private static void pair(Map<String, PropertyDef> props, String id, String domain, String range, String inverse,
                             boolean idInferred) {
        props.put(id, new PropertyDef(id, Optional.of(domain), Optional.of(range), false, Optional.of(inverse),
                false, false, true, Set.of(), idInferred));
        props.put(inverse, new PropertyDef(inverse, Optional.of(range), Optional.of(domain), false, Optional.of(id),
                false, false, false, Set.of(), true));
    }

    static TBox intakeTBox() {
        Map<String, ClassDef> classes = new LinkedHashMap<>();
        for (String c : List.of("Intake", "Declaration", "Broker", "Project", "Finding")) {
            classes.put(c, new ClassDef(c, Set.of(), Set.of(), Set.of()));
        }
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        pair(props, "filedByBroker", "Declaration", "Broker", "brokerFilesDeclaration", false);
        pair(props, "belongsToProject", "Declaration", "Project", "projectIncludesDeclaration", false);
        pair(props, "intakeForDeclaration", "Intake", "Declaration", "declarationHasIntake", false);
        pair(props, "intakeForBroker", "Intake", "Broker", "brokerHasIntake", false);
        pair(props, "intakeForProject", "Intake", "Project", "projectHasIntake", false);
        pair(props, "findingForDeclaration", "Finding", "Declaration", "declarationHasFinding", false);
        props.put("partnerOf", new PropertyDef("partnerOf", Optional.of("Broker"), Optional.of("Broker"),
                false, Optional.of("partneredBy"), false, true, false, Set.of(), false));
        props.put("partneredBy", new PropertyDef("partneredBy", Optional.of("Broker"), Optional.of("Broker"),
                false, Optional.of("partnerOf"), false, false, false, Set.of(), true));
        for (String plain : List.of("agentOf", "allyOf", "knows")) {
            props.put(plain, new PropertyDef(plain, Optional.of("Broker"), Optional.of("Broker"),
                    false, Optional.empty(), false, false, false, Set.of(), false));
        }
        props.put("affiliateOf", new PropertyDef("affiliateOf", Optional.of("Broker"), Optional.of("Broker"),
                false, Optional.of("affiliatedBy"), false, true, false, Set.of(), false));
        props.put("affiliatedBy", new PropertyDef("affiliatedBy", Optional.of("Broker"), Optional.of("Broker"),
                false, Optional.of("affiliateOf"), false, false, false, Set.of(), true));
        props.put("tieOf", new PropertyDef("tieOf", Optional.of("Broker"), Optional.of("Broker"),
                false, Optional.of("tiedBy"), false, true, false, Set.of(), false));
        props.put("tiedBy", new PropertyDef("tiedBy", Optional.of("Broker"), Optional.of("Broker"),
                false, Optional.of("tieBack"), false, true, false, Set.of(), true));
        props.put("tieBack", new PropertyDef("tieBack", Optional.of("Broker"), Optional.of("Broker"),
                false, Optional.of("tiedBy"), false, false, false, Set.of(), true));
        props.put("intakeSeenByBroker", new PropertyDef("intakeSeenByBroker", Optional.of("Intake"), Optional.of("Broker"),
                false, Optional.empty(), false, false, false, Set.of(), false));
        props.put("findingForBroker", new PropertyDef("findingForBroker", Optional.of("Finding"), Optional.of("Broker"),
                false, Optional.empty(), false, false, false, Set.of(), true));
        return new TBox(classes, props, List.of(
                new PropertyChainDef(List.of("intakeForDeclaration", "filedByBroker"), "intakeForBroker"),
                new PropertyChainDef(List.of("intakeForDeclaration", "belongsToProject"), "intakeForProject"),
                new PropertyChainDef(List.of("findingForDeclaration", "filedByBroker"), "findingForBroker"),
                new PropertyChainDef(List.of("agentOf", "allyOf"), "affiliateOf")));
    }
}
