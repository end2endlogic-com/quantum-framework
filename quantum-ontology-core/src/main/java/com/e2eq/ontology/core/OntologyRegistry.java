
package com.e2eq.ontology.core;

import java.util.*;
import java.util.concurrent.Flow;

public interface OntologyRegistry {
    Optional<ClassDef> classOf(String name);
    Optional<PropertyDef> propertyOf(String name);
    List<PropertyChainDef> propertyChains();
    Map<String, PropertyDef> properties();
    Map<String, ClassDef> classes();

    // Extended API — defaulted to preserve binary compatibility
    default TBox getCurrentTBox() { return new TBox(classes(), properties(), propertyChains()); }
    default String getHash() { return "unknown"; }
    default String getTBoxHash() { return TBoxHasher.computeHash(getCurrentTBox()); }
    default Flow.Publisher<Long> versionPublisher() { return subscriber -> subscriber.onComplete(); }
    default boolean needsReindex() { return false; }
    
    // Closure methods for hierarchy navigation (precomputed for efficiency)
    default Set<String> superPropertiesOf(String propertyName) {
        return OntologyClosures.computeSuperProperties(propertyName, this);
    }
    default Set<String> subPropertiesOf(String propertyName) {
        return OntologyClosures.computeSubProperties(propertyName, this);
    }
    default Set<String> ancestorsOf(String className) {
        return OntologyClosures.computeAncestors(className, this);
    }
    default Set<String> descendantsOf(String className) {
        return OntologyClosures.computeDescendants(className, this);
    }
    default Optional<String> inverseOf(String propertyName) {
        return OntologyClosures.computeInverse(propertyName, this);
    }

    static OntologyRegistry inMemory(TBox tbox) { return new InMemoryOntologyRegistry(tbox); }
    record ClassDef(String name, Set<String> parents, Set<String> disjointWith, Set<String> sameAs){}
    // Extended PropertyDef to support subPropertyOf, symmetric, functional, and inferred characteristics
    record PropertyDef(String name,
                       Optional<String> domain,
                       Optional<String> range,
                       boolean inverse,
                       Optional<String> inverseOf,
                       boolean transitive,
                       boolean symmetric,
                       boolean functional,
                       Set<String> subPropertyOf,
                       boolean inferred){
        // Backwards-compatible constructor without inferred field
        public PropertyDef(String name, Optional<String> domain, Optional<String> range,
                          boolean inverse, Optional<String> inverseOf, boolean transitive,
                          boolean symmetric, boolean functional, Set<String> subPropertyOf) {
            this(name, domain, range, inverse, inverseOf, transitive, symmetric, functional, subPropertyOf, false);
        }
    }
    record PropertyChainDef(List<String> chain, String implies){}
    record TBox(Map<String, ClassDef> classes, Map<String, PropertyDef> properties, List<PropertyChainDef> propertyChains){}
}
