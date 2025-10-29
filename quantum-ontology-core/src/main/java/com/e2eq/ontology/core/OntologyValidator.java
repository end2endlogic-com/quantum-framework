package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Basic validator for ontology consistency: reference integrity and chain membership.
 */
public final class OntologyValidator {
    private OntologyValidator() {}

    public static void validate(TBox tbox) {
        if (tbox == null) return;
        Map<String, ClassDef> classes = tbox.classes();
        Map<String, PropertyDef> props = tbox.properties();
        // Validate properties' domain/range/inverseOf
        for (PropertyDef p : props.values()) {
            p.domain().ifPresent(d -> require(classes.containsKey(d), "Unknown class '"+d+"' in domain of "+p.name()));
            p.range().ifPresent(r -> require(classes.containsKey(r), "Unknown class '"+r+"' in range of "+p.name()));
            p.inverseOf().ifPresent(inv -> require(props.containsKey(inv), "Unknown property '"+inv+"' in inverseOf of "+p.name()));
        }
        // Validate chains
        List<PropertyChainDef> chains = tbox.propertyChains();
        if (chains != null) {
            for (PropertyChainDef ch : chains) {
                require(ch.implies() != null && !ch.implies().isBlank(), "Chain implies must be non-empty");
                require(props.containsKey(ch.implies()), "Unknown property '"+ch.implies()+"' in chain implies");
                for (String pid : ch.chain()) {
                    require(props.containsKey(pid), "Unknown property '"+pid+"' in chain for implies "+ch.implies());
                }
            }
        }
        // Optional: if property is transitive, recommend domain==range when both present
        for (PropertyDef p : props.values()) {
            if (p.transitive()) {
                if (p.domain().isPresent() && p.range().isPresent()) {
                    require(p.domain().get().equals(p.range().get()),
                            "Transitive property '"+p.name()+"' should have same domain and range");
                }
            }
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new IllegalArgumentException(msg);
    }
}
