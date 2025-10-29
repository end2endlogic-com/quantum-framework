
package com.e2eq.ontology.core;

import java.util.*;

public interface OntologyRegistry {
    Optional<ClassDef> classOf(String name);
    Optional<PropertyDef> propertyOf(String name);
    List<PropertyChainDef> propertyChains();
    Map<String, PropertyDef> properties();
    static OntologyRegistry inMemory(TBox tbox) { return new InMemoryOntologyRegistry(tbox); }
    record ClassDef(String name, Set<String> parents, Set<String> disjointWith, Set<String> sameAs){}
    // Extended PropertyDef to support subPropertyOf, symmetric, and functional characteristics
    record PropertyDef(String name,
                       Optional<String> domain,
                       Optional<String> range,
                       boolean inverse,
                       Optional<String> inverseOf,
                       boolean transitive,
                       boolean symmetric,
                       boolean functional,
                       Set<String> subPropertyOf){}
    record PropertyChainDef(List<String> chain, String implies){}
    record TBox(Map<String, ClassDef> classes, Map<String, PropertyDef> properties, List<PropertyChainDef> propertyChains){}
}
