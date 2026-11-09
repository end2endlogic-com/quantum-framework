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

        // Classes: union; merge parents/disjoint/sameAs/aliases using union semantics; overlay wins for label
        // and per metadata key
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
                classes.put(id, mergeClass(bc, oc, parents, disjoint, sameAs));
            }
        });

        // Properties: overlay wins for domain/range/inverseOf/label when present; flags are OR'd;
        // subPropertyOf and aliases are unions; metadata keys merge with overlay winning
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
                props.put(id, new PropertyDef(id, domain, range, inverse, inverseOf, transitive, symmetric, functional, supers, inferred,
                        mergeLabel(bp.label(), op.label()),
                        mergeAliases(bp.aliases(), op.aliases()),
                        mergeMetadata(bp.metadata(), op.metadata())));
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

    /**
     * An external stub (see {@link OntologyReferenceCloser}) only stands in for a class another pack
     * owns. When it meets a real declaration, the real class's label and metadata win outright, so the
     * stub's id-label and {@code external=true} marker never leak onto a class someone declares.
     */
    private static ClassDef mergeClass(ClassDef base, ClassDef overlay,
                                       Set<String> parents, Set<String> disjoint, Set<String> sameAs) {
        boolean baseStub = OntologyReferenceCloser.isExternalStub(base);
        boolean overlayStub = OntologyReferenceCloser.isExternalStub(overlay);
        if (overlayStub && !baseStub) {
            return new ClassDef(base.name(), parents, disjoint, sameAs, base.label(),
                    mergeAliases(base.aliases(), overlay.aliases()), base.metadata());
        }
        if (baseStub && !overlayStub) {
            return new ClassDef(overlay.name(), parents, disjoint, sameAs, overlay.label(),
                    mergeAliases(base.aliases(), overlay.aliases()), overlay.metadata());
        }
        return new ClassDef(base.name(), parents, disjoint, sameAs,
                mergeLabel(base.label(), overlay.label()),
                mergeAliases(base.aliases(), overlay.aliases()),
                mergeMetadata(base.metadata(), overlay.metadata()));
    }

    private static String mergeLabel(String base, String overlay) {
        return overlay != null && !overlay.isBlank() ? overlay : base;
    }

    private static Set<String> mergeAliases(Set<String> base, Set<String> overlay) {
        Set<String> aliases = new LinkedHashSet<>(base);
        aliases.addAll(overlay);
        return Collections.unmodifiableSet(aliases);
    }

    private static Map<String, Object> mergeMetadata(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> metadata = new LinkedHashMap<>(base);
        metadata.putAll(overlay);
        return Collections.unmodifiableMap(metadata);
    }
}
