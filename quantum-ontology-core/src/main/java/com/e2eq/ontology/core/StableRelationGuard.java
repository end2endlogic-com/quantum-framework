package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Guards for the ontology relation contract (P1).
 *
 * <ul>
 *   <li><b>Consumer binding</b> ({@link #bindingViolations}): a consumer may bind only to stable
 *       relations. This is a primitive; no consumer is wired to it yet, because every relation is
 *       internal today and enforcing it would break them.</li>
 *   <li><b>Explicit edges</b> ({@link #requireExplicitEdgesAllowed}): a stable derived relation gets
 *       its edges only from its derivation, so explicit edges for it are refused. Internal
 *       derived relations keep today's behaviour and accept explicit edges.</li>
 * </ul>
 */
public final class StableRelationGuard {
    private StableRelationGuard() {}

    /** Thrown when explicit edges name a stable derived relation. */
    public static final class StableDerivedEdgeException extends IllegalArgumentException {
        private final List<String> rejectedPredicates;

        StableDerivedEdgeException(List<String> rejectedPredicates) {
            super(String.join("; ", rejectedPredicates.stream().map(StableRelationGuard::explicitEdgeMessage).toList()));
            this.rejectedPredicates = List.copyOf(rejectedPredicates);
        }

        /** The refused predicates, sorted and de-duplicated. */
        public List<String> rejectedPredicates() {
            return rejectedPredicates;
        }
    }

    /** Binding violation codes shared with the Python implementation. */
    public enum BindingCode {
        BINDING_UNKNOWN_RELATION("binding_unknown_relation"),
        BINDING_INTERNAL_RELATION("binding_internal_relation");

        private final String wireValue;

        BindingCode(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    /** One bound predicate that is not stable. */
    public record BindingViolation(String relation, BindingCode code, String message) {}

    /**
     * One violation per bound predicate that is not stable: a predicate the TBox does not declare is
     * unknown, a declared internal one is internal. Predicates are de-duplicated and reported in
     * first-occurrence order. An empty list means the binding is allowed.
     */
    public static List<BindingViolation> bindingViolations(TBox tbox, Collection<String> predicates, String consumer) {
        Map<String, PropertyDef> props = tbox != null ? tbox.properties() : Map.of();
        List<BindingViolation> out = new ArrayList<>();
        if (predicates == null) return out;
        for (String p : new LinkedHashSet<>(predicates)) {
            PropertyDef def = props.get(p);
            if (def == null) {
                out.add(new BindingViolation(p, BindingCode.BINDING_UNKNOWN_RELATION,
                        "Consumer '" + consumer + "' binds unknown relation '" + p + "'"));
            } else if (!def.isStable()) {
                out.add(new BindingViolation(p, BindingCode.BINDING_INTERNAL_RELATION,
                        "Consumer '" + consumer + "' binds internal relation '" + p
                                + "': only stable relations may be bound"));
            }
        }
        return out;
    }

    /** {@link #bindingViolations(TBox, Collection, String)} over a registry's current TBox. */
    public static List<BindingViolation> bindingViolations(OntologyRegistry registry, Collection<String> predicates, String consumer) {
        return bindingViolations(registry != null ? registry.getCurrentTBox() : null, predicates, consumer);
    }

    /** The shared message for an explicit edge naming a stable derived relation. */
    public static String explicitEdgeMessage(String predicate) {
        return "Explicit edge for stable derived relation '" + predicate
                + "' is not allowed: its edges come only from its derivation";
    }

    /** True when {@code predicate} is a stable derived relation in {@code registry}. */
    public static boolean isStableDerived(OntologyRegistry registry, String predicate) {
        if (registry == null || predicate == null) return false;
        Optional<PropertyDef> def = registry.propertyOf(predicate);
        return def.isPresent() && def.get().isStableDerived();
    }

    /** The stable derived relations among {@code predicates}, sorted and de-duplicated. */
    public static List<String> rejectedDerivedPredicates(OntologyRegistry registry, Collection<String> predicates) {
        TreeSet<String> out = new TreeSet<>();
        if (predicates != null) {
            for (String p : predicates) {
                if (isStableDerived(registry, p)) out.add(p);
            }
        }
        return new ArrayList<>(out);
    }

    /** Provenance rule of edges produced by a {@code ComputedEdgeProvider}. */
    public static final String COMPUTED_RULE = "computed";

    /** True for an edge produced by a {@code ComputedEdgeProvider}: derived by the provider, not asserted. */
    public static boolean isComputed(Reasoner.Edge edge) {
        return edge != null && edge.prov().map(p -> COMPUTED_RULE.equals(p.rule())).orElse(false);
    }

    /**
     * Refuse explicit edges whose predicate is a stable derived relation. Every edge in the explicit
     * input counts as explicit, whatever its {@code inferred} flag; only computed-provider edges
     * ({@link #isComputed}) are exempt. Checks the whole input before the caller writes anything.
     *
     * @throws StableDerivedEdgeException naming every refused predicate
     */
    public static void requireExplicitEdgesAllowed(OntologyRegistry registry, Collection<Reasoner.Edge> explicitEdges) {
        if (registry == null || explicitEdges == null || explicitEdges.isEmpty()) return;
        List<String> predicates = new ArrayList<>();
        for (Reasoner.Edge e : explicitEdges) {
            if (e != null && !isComputed(e)) predicates.add(e.p());
        }
        List<String> rejected = rejectedDerivedPredicates(registry, predicates);
        if (!rejected.isEmpty()) {
            throw new StableDerivedEdgeException(rejected);
        }
    }

    /**
     * Refuse one explicit edge write for {@code predicate}.
     *
     * @throws StableDerivedEdgeException when the predicate is a stable derived relation
     */
    public static void requireExplicitEdgeAllowed(OntologyRegistry registry, String predicate) {
        if (isStableDerived(registry, predicate)) {
            throw new StableDerivedEdgeException(List.of(predicate));
        }
    }
}
