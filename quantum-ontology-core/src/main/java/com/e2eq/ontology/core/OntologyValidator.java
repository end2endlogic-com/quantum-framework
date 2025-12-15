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
