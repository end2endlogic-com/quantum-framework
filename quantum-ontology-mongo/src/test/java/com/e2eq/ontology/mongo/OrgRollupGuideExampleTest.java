package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.ontology.core.EdgeChanges;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.core.OntologyValidator;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The org roll-up example in the ontology guide, run on the engine. Chains must be regular, so the
 * roll-up goes through a stored, non-transitive {@code orgAncestor} closure instead of a transitive
 * {@code ancestorOf} step: {@code [placedBy, memberOf, orgAncestor] => placedUnderOrg}, with
 * {@code placedInOrg subPropertyOf placedUnderOrg} so the order's own org is included.
 */
@QuarkusTest
public class OrgRollupGuideExampleTest {

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
        tenant = new DataDomain("org1", "acc1", "org-rollup-tenant", 0, "user1");
        TenantOntologyTBox doc = new TenantOntologyTBox(rollupTBox(), "rollup-hash", "rollup-yaml", "test", "1.0.0");
        doc.setDataDomain(tenant);
        doc.setRefName("tbox-org-rollup");
        doc.setDisplayName("Org roll-up");
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
    void theGuideExampleIsAValidTBox() {
        OntologyValidator.validate(rollupTBox());
    }

    @Test
    void theTransitiveChainTheGuideUsedToShowIsRejected() {
        Map<String, PropertyDef> props = new LinkedHashMap<>(rollupTBox().properties());
        props.put("ancestorOf", new PropertyDef("ancestorOf", Optional.of("Organization"), Optional.of("Organization"),
                false, Optional.empty(), true, false, false, Set.of()));
        TBox old = new TBox(rollupTBox().classes(), props,
                List.of(new PropertyChainDef(List.of("placedInOrg", "ancestorOf"), "placedInOrg")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> OntologyValidator.validate(old));
        assertEquals("Property chain cannot contain transitive property 'ancestorOf' (non-regular)", ex.getMessage());
    }

    @Test
    void anOrderRollsUpToItsOrgAndEveryAncestor() {
        // OrgA's parent is OrgParent, whose parent is OrgRoot: OrgA stores the closure
        write("OrgA", "Organization", edge("OrgA", "Organization", "orgAncestor", "OrgParent", "Organization"),
                edge("OrgA", "Organization", "orgAncestor", "OrgRoot", "Organization"));
        write("C1", "Customer", edge("C1", "Customer", "memberOf", "OrgA", "Organization"));
        write("O1", "Order", edge("O1", "Order", "placedBy", "C1", "Customer"));

        assertEquals(Set.of("OrgA"), dsts("O1", "placedInOrg"));
        assertEquals(Set.of("OrgA", "OrgParent", "OrgRoot"), dsts("O1", "placedUnderOrg"));
    }

    @Test
    void reParentingAnOrgUpdatesItsOrdersWithoutSavingThem() {
        write("OrgA", "Organization", edge("OrgA", "Organization", "orgAncestor", "OrgParent", "Organization"));
        write("C1", "Customer", edge("C1", "Customer", "memberOf", "OrgA", "Organization"));
        write("O1", "Order", edge("O1", "Order", "placedBy", "C1", "Customer"));
        assertEquals(Set.of("OrgA", "OrgParent"), dsts("O1", "placedUnderOrg"));

        write("OrgA", "Organization", edge("OrgA", "Organization", "orgAncestor", "OrgOther", "Organization"));

        assertEquals(Set.of("OrgA", "OrgOther"), dsts("O1", "placedUnderOrg"));
    }

    /** What OntologyWriteHook.afterPersist does: materialize the entity, then propagate its changes. */
    private void write(String id, String type, Reasoner.Edge... edges) {
        EdgeChanges changes = materializer.apply(tenant, id, type, List.of(edges));
        propagator.propagate(null, tenant, id, changes);
    }

    private Set<String> dsts(String src, String p) {
        // A null realm id reads the security-context realm, where materializer.apply(DataDomain, ...) writes
        return edgeRepo.findBySrc((String) null, tenant, src).stream()
                .filter(e -> p.equals(e.getP()))
                .map(e -> e.getDst())
                .collect(Collectors.toSet());
    }

    private static Reasoner.Edge edge(String src, String srcType, String p, String dst, String dstType) {
        return new Reasoner.Edge(src, srcType, p, dst, dstType, false, Optional.empty());
    }

    static TBox rollupTBox() {
        Map<String, ClassDef> classes = new LinkedHashMap<>();
        for (String c : List.of("Order", "Customer", "Organization")) {
            classes.put(c, new ClassDef(c, Set.of(), Set.of(), Set.of()));
        }
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("placedBy", prop("placedBy", "Order", "Customer", Set.of(), false));
        props.put("memberOf", prop("memberOf", "Customer", "Organization", Set.of(), false));
        props.put("orgAncestor", prop("orgAncestor", "Organization", "Organization", Set.of(), false));
        props.put("placedInOrg", prop("placedInOrg", "Order", "Organization", Set.of("placedUnderOrg"), true));
        props.put("placedUnderOrg", prop("placedUnderOrg", "Order", "Organization", Set.of(), true));
        return new TBox(classes, props, List.of(
                new PropertyChainDef(List.of("placedBy", "memberOf"), "placedInOrg"),
                new PropertyChainDef(List.of("placedBy", "memberOf", "orgAncestor"), "placedUnderOrg")));
    }

    private static PropertyDef prop(String name, String domain, String range, Set<String> supers, boolean inferred) {
        return new PropertyDef(name, Optional.of(domain), Optional.of(range),
                false, Optional.empty(), false, false, false, supers, inferred);
    }
}
