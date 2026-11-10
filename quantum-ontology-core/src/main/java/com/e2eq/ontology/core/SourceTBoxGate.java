package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The stable-relation contract for a TBox built from sources (annotations, Morphia and the YAML
 * pack) rather than admitted (P3c). Pure: the registry provider supplies the last TBox it built for
 * the realm as the baseline and decides what to do with the outcome.
 *
 * <p>The rules are the admission rules ({@link StableRelationDiff}): a source change that removes,
 * demotes, narrows or re-derives a stable relation is admissible only when the pack {@code version:}
 * is greater than the baseline's. A baseline without a recorded version (built before versions were
 * recorded) cannot show a bump, so a breaking change against it is refused. A relation that is stable only because of an annotation is
 * reported too: storage annotations change on refactor, so stable relations belong in the pack.</p>
 */
public final class SourceTBoxGate {
    private SourceTBoxGate() {}

    /** {@code quantum.ontology.stable-diff}: refuse a breaking source TBox, or only log it. */
    public enum Mode {
        ENFORCE, WARN;

        /** Parses the config value; anything other than {@code warn} enforces. */
        public static Mode parse(String value) {
            return value != null && value.trim().toLowerCase(Locale.ROOT).equals("warn") ? WARN : ENFORCE;
        }
    }

    /**
     * @param violations       broken stable-relation guarantees, empty when there is no baseline
     * @param admissible       true when there are none, or the pack version was bumped
     * @param stableOutsidePack stable relations the pack does not declare stable, sorted
     */
    public record Outcome(List<StableRelationDiff.Violation> violations, boolean admissible,
                          List<String> stableOutsidePack) {
        public Outcome {
            violations = List.copyOf(violations);
            stableOutsidePack = List.copyOf(stableOutsidePack);
        }
    }

    /** A source TBox build the gate refused; the registry of {@link #realm()} is not available. */
    public abstract static class RefusedException extends IllegalStateException {
        private final String realm;

        RefusedException(String realm, String message, Throwable cause) {
            super(message, cause);
            this.realm = realm;
        }

        public String realm() {
            return realm;
        }
    }

    /** Thrown when the last built TBox cannot be read, so the contract cannot be checked. */
    public static final class BaselineUnreadableException extends RefusedException {
        public BaselineUnreadableException(String realm, Throwable cause) {
            super(realm, "Ontology for realm '" + realm + "': the last built TBox could not be read ("
                    + (cause != null ? cause.getMessage() : "unknown") + "), so its stable relations cannot be "
                    + "checked (quantum.ontology.stable-diff=enforce)", cause);
        }
    }

    /** Thrown when a source TBox breaks a stable relation without a pack version bump. */
    public static final class StableContractException extends RefusedException {
        private final List<StableRelationDiff.Violation> violations;

        public StableContractException(String realm, List<StableRelationDiff.Violation> violations,
                                       Integer baselineVersion, int candidateVersion, List<String> sourceFailures) {
            super(realm, message(realm, violations, baselineVersion, candidateVersion, sourceFailures), null);
            this.violations = List.copyOf(violations);
        }

        public List<StableRelationDiff.Violation> violations() {
            return violations;
        }

        private static String message(String realm, List<StableRelationDiff.Violation> violations,
                                      Integer baselineVersion, int candidateVersion, List<String> sourceFailures) {
            StringBuilder sb = new StringBuilder("Ontology for realm '").append(realm)
                    .append("' breaks stable relations without a pack version bump (pack version ")
                    .append(candidateVersion).append(", last built ")
                    .append(baselineVersion != null ? baselineVersion.toString() : "unversioned").append("): ");
            sb.append(String.join("; ", violations.stream()
                    .map(v -> v.code().wireValue() + ": " + v.message()).toList()));
            if (!sourceFailures.isEmpty()) {
                sb.append(". Sources that failed to load: ").append(String.join("; ", sourceFailures));
            }
            return sb.append(". Restore the relations, or raise the pack version to publish the change.").toString();
        }
    }

    /**
     * Diff {@code candidate} against {@code baseline} (null when the realm has never been built) and
     * list the stable relations {@code pack} (null when there is no YAML pack) does not declare stable.
     * {@code baselineVersion} is null when the baseline recorded no pack version.
     */
    public static Outcome check(TBox baseline, Integer baselineVersion, TBox candidate, int candidateVersion, TBox pack) {
        List<StableRelationDiff.Violation> violations = StableRelationDiff.violations(baseline, candidate);
        boolean admissible = violations.isEmpty() || (baselineVersion != null
                && StableRelationDiff.admissible(violations, baselineVersion, candidateVersion));
        return new Outcome(violations, admissible, stableOutsidePack(candidate, pack));
    }

    /** Stable relations of {@code merged} that {@code pack} does not declare stable, sorted. */
    public static List<String> stableOutsidePack(TBox merged, TBox pack) {
        List<String> out = new ArrayList<>();
        if (merged == null) return out;
        Map<String, PropertyDef> packProps = pack != null ? pack.properties() : Map.of();
        for (PropertyDef p : new TreeMap<>(merged.properties()).values()) {
            if (!p.isStable()) continue;
            PropertyDef declared = packProps.get(p.name());
            if (declared == null || !declared.isStable()) out.add(p.name());
        }
        return out;
    }
}
