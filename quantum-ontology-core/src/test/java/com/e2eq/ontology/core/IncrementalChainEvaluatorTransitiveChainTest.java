package com.e2eq.ontology.core;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class IncrementalChainEvaluatorTransitiveChainTest {

    private OntologyRegistry buildEcomRegistry() {
        Map<String, OntologyRegistry.ClassDef> classes = Map.of(
                "Order", new OntologyRegistry.ClassDef("Order", Set.of(), Set.of(), Set.of()),
                "Customer", new OntologyRegistry.ClassDef("Customer", Set.of(), Set.of(), Set.of()),
                "Organization", new OntologyRegistry.ClassDef("Organization", Set.of(), Set.of(), Set.of())
        );
        Map<String, OntologyRegistry.PropertyDef> props = new HashMap<>();
        props.put("placedBy", new OntologyRegistry.PropertyDef("placedBy", Optional.of("Order"), Optional.of("Customer"), false, Optional.empty(), false, false, true, Set.of()));
        props.put("memberOf", new OntologyRegistry.PropertyDef("memberOf", Optional.of("Customer"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of()));
        props.put("ancestorOf", new OntologyRegistry.PropertyDef("ancestorOf", Optional.of("Organization"), Optional.of("Organization"), false, Optional.empty(), true, false, false, Set.of()));
        props.put("placedInOrg", new OntologyRegistry.PropertyDef("placedInOrg", Optional.of("Order"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of()));
        List<OntologyRegistry.PropertyChainDef> chains = List.of(
                new OntologyRegistry.PropertyChainDef(List.of("placedBy", "memberOf"), "placedInOrg"),
                new OntologyRegistry.PropertyChainDef(List.of("placedInOrg", "ancestorOf"), "placedInOrg")
        );
        return OntologyRegistry.inMemory(new OntologyRegistry.TBox(classes, props, chains));
    }

    @Test
    public void transitiveStepInChain_isExpandedAndPropagates() {
        String tenant = "t1";
        String order = "O1";
        String cust = "C9";
        String orgA = "OrgA";
        String orgP = "OrgParent";
        String orgG = "OrgGrand";

        InMemoryEdgeStoreTestDouble store = new InMemoryEdgeStoreTestDouble();
        // Base explicit edges
        store.upsert(tenant, "Order", order, "placedBy", "Customer", cust, false, Map.of());
        store.upsert(tenant, "Customer", cust, "memberOf", "Organization", orgA, false, Map.of());
        store.upsert(tenant, "Organization", orgA, "ancestorOf", "Organization", orgP, false, Map.of());
        store.upsert(tenant, "Organization", orgP, "ancestorOf", "Organization", orgG, false, Map.of());

        IncrementalChainEvaluator eval = new IncrementalChainEvaluator();
        OntologyRegistry reg = buildEcomRegistry();
        IncrementalChainEvaluator.Result res = eval.evaluate(tenant, order, Set.of("placedBy", "memberOf", "ancestorOf"), reg, store);

        Set<String> got = new HashSet<>();
        for (EdgeRecord e : res.derivedEdges()) {
            if (e.getSrc().equals(order) && e.getP().equals("placedInOrg")) {
                got.add(e.getDst());
            }
        }
        assertTrue(got.contains(orgA), "direct placedInOrg via first chain");
        assertTrue(got.contains(orgP), "propagates via ancestorOf to parent");
        assertTrue(got.contains(orgG), "propagates via ancestorOf to grandparent");
    }

    @Test
    public void cyclesOnTransitive_doNotLoop() {
        String tenant = "t1";
        String order = "O1";
        String cust = "C9";
        String orgA = "OrgA";
        String orgB = "OrgB";

        InMemoryEdgeStoreTestDouble store = new InMemoryEdgeStoreTestDouble();
        // Base explicit edges
        store.upsert(tenant, "Order", order, "placedBy", "Customer", cust, false, Map.of());
        store.upsert(tenant, "Customer", cust, "memberOf", "Organization", orgA, false, Map.of());
        // cycle on ancestorOf
        store.upsert(tenant, "Organization", orgA, "ancestorOf", "Organization", orgB, false, Map.of());
        store.upsert(tenant, "Organization", orgB, "ancestorOf", "Organization", orgA, false, Map.of());

        IncrementalChainEvaluator eval = new IncrementalChainEvaluator();
        OntologyRegistry reg = buildEcomRegistry();
        IncrementalChainEvaluator.Result res = eval.evaluate(tenant, order, Set.of("placedBy", "memberOf", "ancestorOf"), reg, store);

        long count = res.derivedEdges().stream()
                .filter(e -> e.getSrc().equals(order) && e.getP().equals("placedInOrg"))
                .count();
        assertTrue(count >= 1, "at least direct placedInOrg should be derived");
        // ensure no explosion of infinite results
        assertTrue(count <= 2, "with a 2-node cycle, at most two orgs should be inferred");
    }
}
