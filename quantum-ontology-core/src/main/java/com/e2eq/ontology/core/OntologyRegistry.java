
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
    // label/aliases/metadata carry pack presentation metadata (the unified ontology design
    // pack format); they participate in the canonical hash (CanonicalTBoxHasher).
    record ClassDef(String name, Set<String> parents, Set<String> disjointWith, Set<String> sameAs,
                    String label, Set<String> aliases, Map<String, Object> metadata){
        public ClassDef {
            aliases = aliases != null ? aliases : Set.of();
            metadata = metadata != null ? metadata : Map.of();
        }
        // Backwards-compatible constructor without presentation metadata
        public ClassDef(String name, Set<String> parents, Set<String> disjointWith, Set<String> sameAs) {
            this(name, parents, disjointWith, sameAs, null, Set.of(), Map.of());
        }
    }
    // Extended PropertyDef to support subPropertyOf, symmetric, functional, and inferred characteristics.
    // stability is the relation contract (P1): STABLE relations are what consumers may bind to; it is
    // hashed only when STABLE, so packs that never opt in keep their canonical hash.
    record PropertyDef(String name,
                       Optional<String> domain,
                       Optional<String> range,
                       boolean inverse,
                       Optional<String> inverseOf,
                       boolean transitive,
                       boolean symmetric,
                       boolean functional,
                       Set<String> subPropertyOf,
                       boolean inferred,
                       String label,
                       Set<String> aliases,
                       Map<String, Object> metadata,
                       RelationStability stability){
        public PropertyDef {
            aliases = aliases != null ? aliases : Set.of();
            metadata = metadata != null ? metadata : Map.of();
            stability = stability != null ? stability : RelationStability.INTERNAL;
        }
        // Backwards-compatible constructor without stability (INTERNAL)
        public PropertyDef(String name, Optional<String> domain, Optional<String> range,
                          boolean inverse, Optional<String> inverseOf, boolean transitive,
                          boolean symmetric, boolean functional, Set<String> subPropertyOf,
                          boolean inferred, String label, Set<String> aliases, Map<String, Object> metadata) {
            this(name, domain, range, inverse, inverseOf, transitive, symmetric, functional,
                    subPropertyOf, inferred, label, aliases, metadata, RelationStability.INTERNAL);
        }
        // Backwards-compatible constructor without presentation metadata
        public PropertyDef(String name, Optional<String> domain, Optional<String> range,
                          boolean inverse, Optional<String> inverseOf, boolean transitive,
                          boolean symmetric, boolean functional, Set<String> subPropertyOf,
                          boolean inferred) {
            this(name, domain, range, inverse, inverseOf, transitive, symmetric, functional,
                    subPropertyOf, inferred, null, Set.of(), Map.of());
        }
        // Backwards-compatible constructor without inferred field
        public PropertyDef(String name, Optional<String> domain, Optional<String> range,
                          boolean inverse, Optional<String> inverseOf, boolean transitive,
                          boolean symmetric, boolean functional, Set<String> subPropertyOf) {
            this(name, domain, range, inverse, inverseOf, transitive, symmetric, functional, subPropertyOf, false);
        }
        /** True when consumers may bind to this relation. */
        public boolean isStable() {
            return stability == RelationStability.STABLE;
        }
        /** Derivation view over {@link #inferred()}: DERIVED when inferred, ASSERTED otherwise. */
        public RelationDerivation derivation() {
            return RelationDerivation.of(inferred);
        }
        /** A stable relation whose edges come only from its derivation; explicit edges are refused. */
        public boolean isStableDerived() {
            return isStable() && inferred;
        }
        /** Copy of this definition with a different stability. */
        public PropertyDef withStability(RelationStability newStability) {
            return new PropertyDef(name, domain, range, inverse, inverseOf, transitive, symmetric, functional,
                    subPropertyOf, inferred, label, aliases, metadata, newStability);
        }
    }
    record PropertyChainDef(List<String> chain, String implies){}
    record TBox(Map<String, ClassDef> classes, Map<String, PropertyDef> properties, List<PropertyChainDef> propertyChains){}
}
