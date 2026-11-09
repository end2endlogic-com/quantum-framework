package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;

import java.util.*;

/**
 * Validator for ontology consistency: reference integrity, cycle detection, and illegal combinations.
 */
public final class OntologyValidator {
    private OntologyValidator() {}

    public static void validate(TBox tbox) {
        if (tbox == null) return;
        Map<String, ClassDef> classes = tbox.classes();
        Map<String, PropertyDef> props = tbox.properties();
        
        // Validate properties' domain/range/inverseOf/subPropertyOf
        for (PropertyDef p : props.values()) {
            p.domain().ifPresent(d -> require(classes.containsKey(d), "Unknown class '"+d+"' in domain of "+p.name()));
            p.range().ifPresent(r -> require(classes.containsKey(r), "Unknown class '"+r+"' in range of "+p.name()));
            p.inverseOf().ifPresent(inv -> require(props.containsKey(inv), "Unknown property '"+inv+"' in inverseOf of "+p.name()));
            for (String sp : p.subPropertyOf()) {
                require(props.containsKey(sp), "Unknown property '"+sp+"' in subPropertyOf of "+p.name());
            }
        }
        
        // Detect cycles in subPropertyOf hierarchy
        detectPropertyHierarchyCycles(props);
        
        // Detect cycles in class hierarchy
        detectClassHierarchyCycles(classes);
        
        // Validate chains
        List<PropertyChainDef> chains = tbox.propertyChains();
        if (chains != null) {
            for (PropertyChainDef ch : chains) {
                require(ch.implies() != null && !ch.implies().isBlank(), "Chain implies must be non-empty");
                require(props.containsKey(ch.implies()), "Unknown property '"+ch.implies()+"' in chain implies");
                require(ch.chain() != null && ch.chain().size() >= 2, "Property chain must have at least 2 properties");
                for (String pid : ch.chain()) {
                    require(props.containsKey(pid), "Unknown property '"+pid+"' in chain for implies "+ch.implies());
                }
                // Reject chains involving transitive properties (non-regular)
                PropertyDef implied = props.get(ch.implies());
                require(!implied.transitive(), "Property chain cannot imply transitive property '"+ch.implies()+"' (non-regular)");
                for (String pid : ch.chain()) {
                    PropertyDef chainProp = props.get(pid);
                    require(!chainProp.transitive(), "Property chain cannot contain transitive property '"+pid+"' (non-regular)");
                }
                validateChainEndpointTypes(classes, props, ch);
            }
        }
        
        // Validate transitive properties have same domain and range
        for (PropertyDef p : props.values()) {
            if (p.transitive()) {
                if (p.domain().isPresent() && p.range().isPresent()) {
                    require(p.domain().get().equals(p.range().get()),
                            "Transitive property '"+p.name()+"' must have same domain and range");
                }
            }
        }
    }
    
    /**
     * A chain may only produce edges its implied property admits: the first step's domain must be the
     * implied domain or a subclass of it, and the last step's range the implied range or a subclass of it.
     * Undeclared types, including external stubs added by {@link OntologyReferenceCloser} for classes
     * another pack owns, are left unchecked.
     */
    private static void validateChainEndpointTypes(Map<String, ClassDef> classes, Map<String, PropertyDef> props,
                                                   PropertyChainDef ch) {
        PropertyDef implied = props.get(ch.implies());
        PropertyDef first = props.get(ch.chain().get(0));
        PropertyDef last = props.get(ch.chain().get(ch.chain().size() - 1));
        String label = "Property chain " + ch.chain() + " implies '" + ch.implies() + "'";
        require(isSubclassOrSame(classes, first.domain(), implied.domain()),
                label + ": first step '" + first.name() + "' domain '" + first.domain().orElse(null)
                        + "' is not '" + ch.implies() + "' domain '" + implied.domain().orElse(null)
                        + "' or a subclass of it");
        require(isSubclassOrSame(classes, last.range(), implied.range()),
                label + ": last step '" + last.name() + "' range '" + last.range().orElse(null)
                        + "' is not '" + ch.implies() + "' range '" + implied.range().orElse(null)
                        + "' or a subclass of it");
    }

    private static boolean isSubclassOrSame(Map<String, ClassDef> classes, Optional<String> sub, Optional<String> sup) {
        if (sub.isEmpty() || sup.isEmpty() || sub.get().equals(sup.get())) return true;
        if (!isLocallyDeclared(classes, sub.get()) || !isLocallyDeclared(classes, sup.get())) return true;
        // Parents are followed upward only; sameAs is an equivalence, so it is followed in both directions.
        Map<String, Set<String>> equivalents = new HashMap<>();
        for (ClassDef def : classes.values()) {
            for (String other : def.sameAs()) {
                equivalents.computeIfAbsent(def.name(), k -> new HashSet<>()).add(other);
                equivalents.computeIfAbsent(other, k -> new HashSet<>()).add(def.name());
            }
        }
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(sub.get());
        visited.add(sub.get());
        while (!stack.isEmpty()) {
            String current = stack.pop();
            List<String> related = new ArrayList<>(equivalents.getOrDefault(current, Set.of()));
            ClassDef def = classes.get(current);
            if (def != null) related.addAll(def.parents());
            for (String next : related) {
                if (next.equals(sup.get())) return true;
                if (visited.add(next)) stack.push(next);
            }
        }
        return false;
    }

    private static boolean isLocallyDeclared(Map<String, ClassDef> classes, String classId) {
        ClassDef def = classes.get(classId);
        return def != null && !OntologyReferenceCloser.isExternalStub(def);
    }

    private static void detectPropertyHierarchyCycles(Map<String, PropertyDef> props) {
        for (String start : props.keySet()) {
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);
            visited.add(start);
            
            while (!stack.isEmpty()) {
                String current = stack.pop();
                PropertyDef def = props.get(current);
                if (def == null) continue;
                
                for (String parent : def.subPropertyOf()) {
                    if (parent.equals(start)) {
                        throw new IllegalArgumentException("Cycle detected in subPropertyOf hierarchy involving '"+start+"'");
                    }
                    if (!visited.contains(parent)) {
                        visited.add(parent);
                        stack.push(parent);
                    }
                }
            }
        }
    }
    
    private static void detectClassHierarchyCycles(Map<String, ClassDef> classes) {
        for (String start : classes.keySet()) {
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);
            visited.add(start);
            
            while (!stack.isEmpty()) {
                String current = stack.pop();
                ClassDef def = classes.get(current);
                if (def == null) continue;
                
                for (String parent : def.parents()) {
                    if (parent.equals(start)) {
                        throw new IllegalArgumentException("Cycle detected in class hierarchy involving '"+start+"'");
                    }
                    if (!visited.contains(parent)) {
                        visited.add(parent);
                        stack.push(parent);
                    }
                }
            }
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new IllegalArgumentException(msg);
    }
}
