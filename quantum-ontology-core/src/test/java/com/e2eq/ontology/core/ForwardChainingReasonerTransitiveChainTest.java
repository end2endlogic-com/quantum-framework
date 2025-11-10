package com.e2eq.ontology.core;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ForwardChainingReasonerTransitiveChainTest {

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

        OntologyRegistry.TBox tbox = new OntologyRegistry.TBox(classes, props, chains);
        return OntologyRegistry.inMemory(tbox);
    }

    @Test
    public void chainStepWithTransitivePredicateExpandsClosure() {
        OntologyRegistry reg = buildEcomRegistry();
        ForwardChainingReasoner r = new ForwardChainingReasoner();

        String tenant = "t1";
        String order = "O1";
        String cust = "C9";
        String orgA = "OrgA";
        String orgP = "OrgParent";
        String orgG = "OrgGrand";

        // Snapshot explicit edges
        List<Reasoner.Edge> explicit = List.of(
                new Reasoner.Edge(order, "Order", "placedBy", cust, "Customer", false, Optional.empty()),
                new Reasoner.Edge(cust, "Customer", "memberOf", orgA, "Organization", false, Optional.empty()),
                new Reasoner.Edge(orgA, "Organization", "ancestorOf", orgP, "Organization", false, Optional.empty()),
                new Reasoner.Edge(orgP, "Organization", "ancestorOf", orgG, "Organization", false, Optional.empty())
        );

        Reasoner.EntitySnapshot snap = new Reasoner.EntitySnapshot(tenant, order, "Order", explicit);
        Reasoner.InferenceResult res = r.infer(snap, reg);

        Set<String> got = new HashSet<>();
        for (Reasoner.Edge e : res.addEdges()) {
            got.add(e.srcId()+"|"+e.p()+"|"+e.dstId());
        }

        assertTrue(got.contains(order+"|placedInOrg|"+orgA), "direct org via first chain");
        assertTrue(got.contains(order+"|placedInOrg|"+orgP), "should propagate to parent org via ancestorOf");
        assertTrue(got.contains(order+"|placedInOrg|"+orgG), "should propagate to grandparent via transitive expansion");
    }
}
