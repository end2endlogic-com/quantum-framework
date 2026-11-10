package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.ontology.core.EdgeChanges;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DRed under a recursive chain: with {@code [linkedTo, hasTag] => reaches} and
 * {@code [linkedTo, reaches] => reaches}, two nodes linked to each other must not keep justifying
 * each other's {@code reaches} edge once the tag that supported both is gone.
 */
@QuarkusTest
public class RecursiveChainPropagationTest {

    @Inject
    TenantOntologyTBoxRepo tboxRepo;

    @Inject
    TenantOntologyRegistryProvider provider;

    @Inject
    OntologyMaterializer materializer;

    @Inject
    ChainPropagator propagator;

    @Inject
    OntologyEdgeRepo edgeRepo;

    private DataDomain tenant;
    private SecurityCallScope.Scope ignoredRulesScope;

    @BeforeEach
    void setup() {
        ignoredRulesScope = SecurityCallScope.openIgnoringRules();
        tboxRepo.deleteAll();
        edgeRepo.deleteAll();
        provider.clearCache();
        tenant = new DataDomain("org1", "acc1", "recursive-chain-tenant", 0, "user1");
        TenantOntologyTBox doc = new TenantOntologyTBox(reachTBox(), "reach-hash", "reach-yaml", "test", "1.0.0");
        doc.setDataDomain(tenant);
        doc.setRefName("tbox-recursive-chain");
        doc.setDisplayName("Recursive chain");
        tboxRepo.save(doc);
    }

    @AfterEach
    void cleanup() {
        provider.clearCache();
        if (ignoredRulesScope != null) {
            ignoredRulesScope.close();
        }
    }

    @Test
    void aCycleDerivesReachThroughTheTag() {
        cycleWithTag();

        assertTrue(reaches("a", "t1"));
        assertTrue(reaches("b", "t1"));
    }

    @Test
    void removingTheTagRetractsReachOnBothSidesOfTheCycle() {
        cycleWithTag();

        ChainPropagator.Result result = write("a", edge("a", "linkedTo", "b"));

        assertFalse(reaches("b", "t1"), "b kept the reach a's stale edge justified");
        assertFalse(reaches("a", "t1"), "a kept the reach b's stale edge justified");
        assertFalse(result.truncated());
    }

    @Test
    void anIndependentJustificationSurvivesTheRetraction() {
        cycleWithTag();
        write("c", edge("c", "hasTag", "t1"));
        write("b", edge("b", "linkedTo", "a"), edge("b", "linkedTo", "c"));
        assertTrue(reaches("b", "t1"));

        write("a", edge("a", "linkedTo", "b"));

        assertTrue(reaches("b", "t1"), "b still reaches t1 through c");
        assertTrue(reaches("a", "t1"), "a still reaches t1 through b");
    }

    @Test
    void headsBeyondTheBudgetAreNotOverDeletedAndAreNamed() {
        cycleWithTag();
        EdgeChanges changes = materializer.apply(tenant, "a", "Node", List.of(edge("a", "linkedTo", "b")));

        ChainPropagator.Result result = new ChainPropagator(materializer, edgeRepo, provider, 1)
                .propagate(null, tenant, "a", changes);

        assertTrue(result.truncated());
        assertTrue(result.pendingHeads().containsAll(Set.of("a", "b")), result.pendingHeads().toString());
    }

    /** a and b link to each other; a carries the tag. */
    private void cycleWithTag() {
        write("a", edge("a", "linkedTo", "b"), edge("a", "hasTag", "t1"));
        write("b", edge("b", "linkedTo", "a"));
    }

    private ChainPropagator.Result write(String id, Reasoner.Edge... edges) {
        EdgeChanges changes = materializer.apply(tenant, id, "Node", List.of(edges));
        return propagator.propagate(null, tenant, id, changes);
    }

    private boolean reaches(String node, String tag) {
        return edgeRepo.findBySrc((String) null, tenant, node).stream()
                .anyMatch(e -> "reaches".equals(e.getP()) && tag.equals(e.getDst()) && e.isInferred());
    }

    private static Reasoner.Edge edge(String src, String p, String dst) {
        return new Reasoner.Edge(src, "Node", p, dst, "hasTag".equals(p) ? "Tag" : "Node", false, Optional.empty());
    }

    private static TBox reachTBox() {
        Map<String, ClassDef> classes = Map.of(
                "Node", new ClassDef("Node", Set.of(), Set.of(), Set.of()),
                "Tag", new ClassDef("Tag", Set.of(), Set.of(), Set.of()));
        Map<String, PropertyDef> props = Map.of(
                "linkedTo", prop("linkedTo", "Node", "Node", false),
                "hasTag", prop("hasTag", "Node", "Tag", false),
                "reaches", prop("reaches", "Node", "Tag", true));
        return new TBox(classes, props, List.of(
                new PropertyChainDef(List.of("linkedTo", "hasTag"), "reaches"),
                new PropertyChainDef(List.of("linkedTo", "reaches"), "reaches")));
    }

    private static PropertyDef prop(String name, String domain, String range, boolean inferred) {
        return new PropertyDef(name, Optional.of(domain), Optional.of(range),
                false, Optional.empty(), false, false, false, Set.of(), inferred);
    }
}
