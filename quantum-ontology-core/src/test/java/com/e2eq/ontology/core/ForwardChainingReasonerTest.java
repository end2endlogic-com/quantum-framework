package com.e2eq.ontology.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class ForwardChainingReasonerTest {

    private OntologyRegistry buildEcomRegistry() {
        Map<String, OntologyRegistry.ClassDef> classes = Map.of(
                "Order", new OntologyRegistry.ClassDef("Order", Set.of(), Set.of(), Set.of()),
                "Customer", new OntologyRegistry.ClassDef("Customer", Set.of(), Set.of(), Set.of()),
                "Organization", new OntologyRegistry.ClassDef("Organization", Set.of(), Set.of(), Set.of()),
                "Address", new OntologyRegistry.ClassDef("Address", Set.of(), Set.of(), Set.of()),
                "Region", new OntologyRegistry.ClassDef("Region", Set.of(), Set.of(), Set.of())
        );

        Map<String, OntologyRegistry.PropertyDef> props = new HashMap<>();
        props.put("placedBy", new OntologyRegistry.PropertyDef("placedBy", Optional.of("Order"), Optional.of("Customer"), false, Optional.empty(), false, false, true, Set.of()));
        props.put("memberOf", new OntologyRegistry.PropertyDef("memberOf", Optional.of("Customer"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of()));
        props.put("ancestorOf", new OntologyRegistry.PropertyDef("ancestorOf", Optional.of("Organization"), Optional.of("Organization"), false, Optional.empty(), true, false, false, Set.of()));
        props.put("placedInOrg", new OntologyRegistry.PropertyDef("placedInOrg", Optional.of("Order"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of()));
        props.put("parentOf", new OntologyRegistry.PropertyDef("parentOf", Optional.of("Organization"), Optional.of("Organization"), false, Optional.of("childOf"), false, false, false, Set.of()));
        props.put("childOf", new OntologyRegistry.PropertyDef("childOf", Optional.of("Organization"), Optional.of("Organization"), true, Optional.empty(), false, false, false, Set.of()));

        List<OntologyRegistry.PropertyChainDef> chains = List.of(
                new OntologyRegistry.PropertyChainDef(List.of("placedBy", "memberOf"), "placedInOrg"),
                // closure to include ancestors
                new OntologyRegistry.PropertyChainDef(List.of("placedInOrg", "ancestorOf"), "placedInOrg")
        );

        OntologyRegistry.TBox tbox = new OntologyRegistry.TBox(classes, props, chains);
        return OntologyRegistry.inMemory(tbox);
    }

    @Test
    public void testPropertyChainsAndTransitive() {
        OntologyRegistry reg = buildEcomRegistry();
        ForwardChainingReasoner r = new ForwardChainingReasoner();

        String tenant = "t1";
        String order = "O1";
        String cust = "C9";
        String orgA = "OrgA";
        String orgP = "OrgParent";

        List<Reasoner.Edge> explicit = List.of(
                new Reasoner.Edge(order, "Order", "placedBy", cust, "Customer", false, Optional.empty()),
                new Reasoner.Edge(cust, "Customer", "memberOf", orgA, "Organization", false, Optional.empty()),
                new Reasoner.Edge(orgA, "Organization", "ancestorOf", orgP, "Organization", false, Optional.empty())
        );

        Reasoner.EntitySnapshot snap = new Reasoner.EntitySnapshot(tenant, order, "Order", explicit);
        Reasoner.InferenceResult res = r.infer(snap, reg);

        Set<String> got = new HashSet<>();
        for (Reasoner.Edge e : res.addEdges()) {
            got.add(e.srcId()+"|"+e.p()+"|"+e.dstId());
            assertTrue(e.inferred());
            assertTrue(e.prov().isPresent());
        }

        assertTrue(got.contains(order+"|placedInOrg|"+orgA), "placedInOrg direct via chain");
    }

    @Test
    public void testInverseEdges() {
        Map<String, OntologyRegistry.ClassDef> classes = Map.of(
                "Organization", new OntologyRegistry.ClassDef("Organization", Set.of(), Set.of(), Set.of())
        );
        Map<String, OntologyRegistry.PropertyDef> props = new HashMap<>();
        props.put("parentOf", new OntologyRegistry.PropertyDef("parentOf", Optional.of("Organization"), Optional.of("Organization"), false, Optional.of("childOf"), false, false, false, Set.of()));
        props.put("childOf", new OntologyRegistry.PropertyDef("childOf", Optional.of("Organization"), Optional.of("Organization"), true, Optional.empty(), false, false, false, Set.of()));
        OntologyRegistry.TBox tbox = new OntologyRegistry.TBox(classes, props, List.of());
        OntologyRegistry reg = OntologyRegistry.inMemory(tbox);

        ForwardChainingReasoner r = new ForwardChainingReasoner();
        String tenant = "t1";
        List<Reasoner.Edge> explicit = List.of(
                new Reasoner.Edge("OrgA", "Organization", "parentOf", "OrgB", "Organization", false, Optional.empty())
        );
        Reasoner.EntitySnapshot snap = new Reasoner.EntitySnapshot(tenant, "OrgA", "Organization", explicit);
        Reasoner.InferenceResult res = r.infer(snap, reg);

        boolean found = res.addEdges().stream().anyMatch(e -> e.srcId().equals("OrgB") && e.p().equals("childOf") && e.dstId().equals("OrgA"));
        assertTrue(found, "inverse edge childOf should be inferred");
    }
}
