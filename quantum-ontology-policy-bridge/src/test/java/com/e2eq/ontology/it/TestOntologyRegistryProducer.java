package com.e2eq.ontology.it;

import com.e2eq.ontology.core.OntologyRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.util.*;

@ApplicationScoped
public class TestOntologyRegistryProducer {

    @Produces @Singleton
    public OntologyRegistry ontologyRegistry() {
        Map<String, OntologyRegistry.ClassDef> classes = Map.of(
                "Order", new OntologyRegistry.ClassDef("Order", Set.of(), Set.of(), Set.of()),
                "Customer", new OntologyRegistry.ClassDef("Customer", Set.of(), Set.of(), Set.of()),
                "Organization", new OntologyRegistry.ClassDef("Organization", Set.of(), Set.of(), Set.of())
        );
        Map<String, OntologyRegistry.PropertyDef> props = new HashMap<>();
        props.put("placedBy", new OntologyRegistry.PropertyDef("placedBy", Optional.of("Order"), Optional.of("Customer"), false, Optional.empty(), false, false, true, Set.of()));
        props.put("memberOf", new OntologyRegistry.PropertyDef("memberOf", Optional.of("Customer"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of()));
        // super-property for organizational membership of orders (more generic than placedInOrg)
        props.put("inOrg", new OntologyRegistry.PropertyDef("inOrg", Optional.of("Order"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of()));
        // placedInOrg is a sub-property of inOrg
        props.put("placedInOrg", new OntologyRegistry.PropertyDef("placedInOrg", Optional.of("Order"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of("inOrg")));
        // symmetric relationship between organizations
        props.put("peerOf", new OntologyRegistry.PropertyDef("peerOf", Optional.of("Organization"), Optional.of("Organization"), false, Optional.empty(), false, true, false, Set.of()));

        List<OntologyRegistry.PropertyChainDef> chains = List.of(
                new OntologyRegistry.PropertyChainDef(List.of("placedBy", "memberOf"), "placedInOrg")
        );

        return OntologyRegistry.inMemory(new OntologyRegistry.TBox(classes, props, chains));
    }
}
