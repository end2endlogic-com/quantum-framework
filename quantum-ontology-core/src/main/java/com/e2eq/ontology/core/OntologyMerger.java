package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;

import java.util.*;

/**
 * Merges two ontology TBoxes. The overlay augments/overrides the base.
 */
public final class OntologyMerger {
    private OntologyMerger() {}

    public static TBox merge(TBox base, TBox overlay) {
        if (base == null) return overlay;
        if (overlay == null) return base;

        // Classes: union; merge parents/disjoint/sameAs using union semantics
        Map<String, ClassDef> classes = new LinkedHashMap<>(base.classes());
        overlay.classes().forEach((id, oc) -> {
            ClassDef bc = classes.get(id);
            if (bc == null) {
                classes.put(id, oc);
            } else {
                Set<String> parents = new LinkedHashSet<>(bc.parents());
                parents.addAll(oc.parents());
                Set<String> disjoint = new LinkedHashSet<>(bc.disjointWith());
                disjoint.addAll(oc.disjointWith());
                Set<String> sameAs = new LinkedHashSet<>(bc.sameAs());
                sameAs.addAll(oc.sameAs());
                classes.put(id, new ClassDef(id, parents, disjoint, sameAs));
            }
        });

        // Properties: overlay wins for domain/range/inverseOf when present; flags are OR'd; subPropertyOf is union
        Map<String, PropertyDef> props = new LinkedHashMap<>(base.properties());
        overlay.properties().forEach((id, op) -> {
            PropertyDef bp = props.get(id);
            if (bp == null) {
                props.put(id, op);
            } else {
                Optional<String> domain = op.domain().isPresent() ? op.domain() : bp.domain();
                Optional<String> range = op.range().isPresent() ? op.range() : bp.range();
                boolean inverse = op.inverse() || bp.inverse();
                Optional<String> inverseOf = op.inverseOf().isPresent() ? op.inverseOf() : bp.inverseOf();
                boolean transitive = op.transitive() || bp.transitive();
                boolean symmetric = op.symmetric() || bp.symmetric();
                boolean functional = op.functional() || bp.functional();
                Set<String> supers = new LinkedHashSet<>(bp.subPropertyOf());
                supers.addAll(op.subPropertyOf());
                boolean inferred = op.inferred() || bp.inferred();
                props.put(id, new PropertyDef(id, domain, range, inverse, inverseOf, transitive, symmetric, functional, supers, inferred));
            }
        });

        // Chains: union with de-duplication
        List<PropertyChainDef> chains = new ArrayList<>(base.propertyChains());
        for (PropertyChainDef ch : overlay.propertyChains()) {
            boolean exists = chains.stream().anyMatch(c -> c.implies().equals(ch.implies()) && c.chain().equals(ch.chain()));
            if (!exists) chains.add(ch);
        }

        OntologyRegistry.TBox merged = new TBox(classes, props, chains);
        OntologyValidator.validate(merged);
        return merged;
    }
}
