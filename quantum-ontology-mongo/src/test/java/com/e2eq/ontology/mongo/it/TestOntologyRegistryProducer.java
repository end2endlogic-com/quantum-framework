package com.e2eq.ontology.mongo.it;

import com.e2eq.ontology.core.OntologyRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;

import java.util.*;

@ApplicationScoped
public class TestOntologyRegistryProducer {

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    @Produces @Singleton
    public OntologyRegistry ontologyRegistry() {
        Map<String, OntologyRegistry.ClassDef> classes = new HashMap<>();
        classes.put("Order", new OntologyRegistry.ClassDef("Order", Set.of(), Set.of(), Set.of()));
        classes.put("Customer", new OntologyRegistry.ClassDef("Customer", Set.of(), Set.of(), Set.of()));
        classes.put("Organization", new OntologyRegistry.ClassDef("Organization", Set.of(), Set.of(), Set.of()));
        // Chain inference test classes
        classes.put("ChainClassA", new OntologyRegistry.ClassDef("ChainClassA", Set.of(), Set.of(), Set.of()));
        classes.put("ChainClassB", new OntologyRegistry.ClassDef("ChainClassB", Set.of(), Set.of(), Set.of()));
        classes.put("ChainClassC", new OntologyRegistry.ClassDef("ChainClassC", Set.of(), Set.of(), Set.of()));
        classes.put("ChainClassD", new OntologyRegistry.ClassDef("ChainClassD", Set.of(), Set.of(), Set.of()));

        Map<String, OntologyRegistry.PropertyDef> props = new HashMap<>();
        props.put("placedBy", new OntologyRegistry.PropertyDef("placedBy", Optional.of("Order"), Optional.of("Customer"), false, Optional.empty(), false, false, true, Set.of()));
        props.put("placed", new OntologyRegistry.PropertyDef("placed", Optional.of("Customer"), Optional.of("Order"), true, Optional.of("placedBy"), false, false, false, Set.of()));
        props.put("memberOf", new OntologyRegistry.PropertyDef("memberOf", Optional.of("Customer"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of()));
        // super-property for organizational membership of orders (more generic than placedInOrg)
        props.put("inOrg", new OntologyRegistry.PropertyDef("inOrg", Optional.of("Order"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of()));
        // placedInOrg is a sub-property of inOrg
        props.put("placedInOrg", new OntologyRegistry.PropertyDef("placedInOrg", Optional.of("Order"), Optional.of("Organization"), false, Optional.empty(), false, false, false, Set.of("inOrg")));

        // Chain inference test properties
        props.put("refersToB", new OntologyRegistry.PropertyDef("refersToB", Optional.of("ChainClassA"), Optional.of("ChainClassB"), false, Optional.empty(), false, false, true, Set.of()));
        props.put("refersToC", new OntologyRegistry.PropertyDef("refersToC", Optional.of("ChainClassB"), Optional.of("ChainClassC"), false, Optional.empty(), false, false, true, Set.of()));
        props.put("refersToD", new OntologyRegistry.PropertyDef("refersToD", Optional.of("ChainClassB"), Optional.of("ChainClassD"), false, Optional.empty(), false, false, true, Set.of()));
        // Implied properties from chains (marked as inferred)
        props.put("impliedRefersToC", new OntologyRegistry.PropertyDef("impliedRefersToC", Optional.of("ChainClassA"), Optional.of("ChainClassC"), false, Optional.empty(), false, false, false, Set.of(), true));
        props.put("impliedRefersToD", new OntologyRegistry.PropertyDef("impliedRefersToD", Optional.of("ChainClassA"), Optional.of("ChainClassD"), false, Optional.empty(), false, false, false, Set.of(), true));

        List<OntologyRegistry.PropertyChainDef> chains = List.of(
                new OntologyRegistry.PropertyChainDef(List.of("placedBy", "memberOf"), "placedInOrg"),
                // Chain inference test: A->B->C implies A->C
                new OntologyRegistry.PropertyChainDef(List.of("refersToB", "refersToC"), "impliedRefersToC"),
                // Chain inference test: A->B->D implies A->D
                new OntologyRegistry.PropertyChainDef(List.of("refersToB", "refersToD"), "impliedRefersToD")
        );

        return OntologyRegistry.inMemory(new OntologyRegistry.TBox(classes, props, chains));
    }
}
