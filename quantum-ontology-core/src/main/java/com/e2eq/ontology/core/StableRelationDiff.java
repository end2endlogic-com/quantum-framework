package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Admission diff for stable relations (the ontology relation contract, P1). Pure: it compares an
 * active TBox with a candidate and reports what the candidate breaks for consumers bound to the
 * active stable relations. Pinned by {@code conformance-vectors/stable-relation-diff.json}, which the
 * Python implementation runs too, so codes and messages are identical in both runtimes.
 *
 * <p>Allowed without a version bump: adding, removing or rewriting the chains beneath a stable
 * relation, adding stable relations, widening domain or range, and label/alias/metadata changes.
 * The diff does not validate the candidate; {@link OntologyValidator} runs separately. Enforcement
 * (refusing a non-admissible candidate) belongs to the admission service, not this class.
 */
public final class StableRelationDiff {
    private StableRelationDiff() {}

    /** Violation codes, in the order they are reported for one relation. */
    public enum Code {
        STABLE_RELATION_REMOVED("stable_relation_removed"),
        STABLE_RELATION_DEMOTED("stable_relation_demoted"),
        STABLE_RELATION_DOMAIN_NARROWED("stable_relation_domain_narrowed"),
        STABLE_RELATION_RANGE_NARROWED("stable_relation_range_narrowed"),
        STABLE_RELATION_DERIVATION_CHANGED("stable_relation_derivation_changed");

        private final String wireValue;

        Code(String wireValue) {
            this.wireValue = wireValue;
        }

        /** Lowercase snake_case code shared with the Python implementation. */
        public String wireValue() {
            return wireValue;
        }
    }

    /** One broken stable-relation guarantee. */
    public record Violation(String relation, Code code, String message) {}

    /** Every violation plus the admission decision. */
    public record Result(List<Violation> violations, boolean admissible) {
        public Result {
            violations = List.copyOf(violations);
        }
    }

    /**
     * Violations for every relation that is stable in {@code active}, in relation-name order and,
     * within a relation, in {@link Code} order. A removed relation reports only its removal; otherwise
     * every check runs, so one relation can produce several violations.
     */
    public static List<Violation> violations(TBox active, TBox candidate) {
        List<Violation> out = new ArrayList<>();
        if (active == null) return out;
        Map<String, PropertyDef> candidateProps = candidate != null ? candidate.properties() : Map.of();
        Map<String, OntologyRegistry.ClassDef> candidateClasses = candidate != null ? candidate.classes() : Map.of();
        for (PropertyDef old : new TreeMap<>(active.properties()).values()) {
            if (!old.isStable()) continue;
            String r = old.name();
            PropertyDef now = candidateProps.get(r);
            if (now == null) {
                out.add(new Violation(r, Code.STABLE_RELATION_REMOVED, "Stable relation '" + r + "' was removed"));
                continue;
            }
            if (!now.isStable()) {
                out.add(new Violation(r, Code.STABLE_RELATION_DEMOTED, "Stable relation '" + r + "' was made internal"));
            }
            if (narrowed(candidateClasses, old.domain(), now.domain())) {
                out.add(new Violation(r, Code.STABLE_RELATION_DOMAIN_NARROWED, "Stable relation '" + r
                        + "' domain narrowed from '" + old.domain().get() + "' to '" + now.domain().get() + "'"));
            }
            if (narrowed(candidateClasses, old.range(), now.range())) {
                out.add(new Violation(r, Code.STABLE_RELATION_RANGE_NARROWED, "Stable relation '" + r
                        + "' range narrowed from '" + old.range().get() + "' to '" + now.range().get() + "'"));
            }
            if (old.inferred() != now.inferred()) {
                out.add(new Violation(r, Code.STABLE_RELATION_DERIVATION_CHANGED, "Stable relation '" + r
                        + "' derivation changed from '" + old.derivation().wireValue() + "' to '"
                        + now.derivation().wireValue() + "'"));
            }
        }
        return out;
    }

    /**
     * Violations plus the admission decision: admissible when there are no violations, or when the
     * candidate pack version is greater than the active pack version.
     */
    public static Result diff(TBox active, int activeVersion, TBox candidate, int candidateVersion) {
        List<Violation> violations = violations(active, candidate);
        return new Result(violations, admissible(violations, activeVersion, candidateVersion));
    }

    /** The admission decision for already computed violations. */
    public static boolean admissible(List<Violation> violations, int activeVersion, int candidateVersion) {
        return violations.isEmpty() || candidateVersion > activeVersion;
    }

    /**
     * The active type is kept when the candidate type equals it or is a superclass of it in the
     * candidate's hierarchy (strict: parents upward, {@code sameAs} both ways). A candidate without a
     * type admits everything, so it never narrows. A stable relation without an active type already
     * fails validation, so that side is skipped. An undeclared class reaches only itself (and its
     * {@code sameAs} partners), so an unchanged undeclared type is never narrowed.
     */
    private static boolean narrowed(Map<String, OntologyRegistry.ClassDef> candidateClasses,
                                    Optional<String> active, Optional<String> candidate) {
        if (active.isEmpty() || candidate.isEmpty()) return false;
        return !OntologyValidator.reachesClass(candidateClasses, active.get(), candidate.get());
    }
}
