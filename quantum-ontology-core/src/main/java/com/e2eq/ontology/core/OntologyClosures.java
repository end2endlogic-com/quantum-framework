package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;

import java.util.*;

/**
 * Computes transitive closures for ontology hierarchies.
 * Methods can be precomputed and cached for efficiency.
 */
public final class OntologyClosures {
    private OntologyClosures() {}
    
    /**
     * Returns all super-properties of the given property (transitive closure of subPropertyOf).
     */
    public static Set<String> computeSuperProperties(String propertyName, OntologyRegistry registry) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(propertyName);
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            Optional<PropertyDef> def = registry.propertyOf(current);
            if (def.isEmpty()) continue;
            
            for (String parent : def.get().subPropertyOf()) {
                if (result.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return result;
    }
    
    /**
     * Returns all sub-properties of the given property (inverse of subPropertyOf).
     */
    public static Set<String> computeSubProperties(String propertyName, OntologyRegistry registry) {
        Set<String> result = new LinkedHashSet<>();
        
        // Build inverse map
        for (PropertyDef p : registry.properties().values()) {
            if (p.subPropertyOf().contains(propertyName)) {
                result.add(p.name());
                // Recursively add sub-properties
                result.addAll(computeSubProperties(p.name(), registry));
            }
        }
        return result;
    }
    
    /**
     * Returns all ancestor classes of the given class (transitive closure of parents).
     */
    public static Set<String> computeAncestors(String className, OntologyRegistry registry) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(className);
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            Optional<ClassDef> def = registry.classOf(current);
            if (def.isEmpty()) continue;
            
            for (String parent : def.get().parents()) {
                if (result.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return result;
    }
    
    /**
     * Returns all descendant classes of the given class (inverse of parents).
     */
    public static Set<String> computeDescendants(String className, OntologyRegistry registry) {
        Set<String> result = new LinkedHashSet<>();
        
        // Build inverse map
        for (ClassDef c : registry.classes().values()) {
            if (c.parents().contains(className)) {
                result.add(c.name());
                // Recursively add descendants
                result.addAll(computeDescendants(c.name(), registry));
            }
        }
        return result;
    }
    
    /**
     * Returns the inverse property of the given property.
     * Checks both direct inverseOf declaration and reverse lookup.
     */
    public static Optional<String> computeInverse(String propertyName, OntologyRegistry registry) {
        Optional<PropertyDef> def = registry.propertyOf(propertyName);
        if (def.isEmpty()) return Optional.empty();
        
        // Check direct inverse
        if (def.get().inverseOf().isPresent()) {
            return def.get().inverseOf();
        }
        
        // Check reverse: find property that declares this as its inverse
        for (PropertyDef p : registry.properties().values()) {
            if (p.inverseOf().isPresent() && p.inverseOf().get().equals(propertyName)) {
                return Optional.of(p.name());
            }
        }
        
        return Optional.empty();
    }
}
