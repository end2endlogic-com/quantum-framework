package com.e2eq.ontology.runtime;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * B5 vocabulary tier: explicit 3-state result of resolving a realm's
 * admitted-predicate set for security-rule (policy) use.
 *
 * <p>This type exists to close a fail-OPEN security downgrade. The previous
 * 2-state {@code Optional<Set<String>>} contract funneled BOTH "this realm has
 * no governed admitted set (legacy)" AND "the Mongo read threw" into
 * {@code Optional.empty()}, which the policy guard mapped to "all declared
 * predicates admitted". The net effect: once a realm became GOVERNED, a
 * transient Mongo read failure silently downgraded it to accept-every-predicate.
 * Distinguishing the read-failure state lets the guard fail CLOSED instead.</p>
 *
 * <ul>
 *   <li>{@link Kind#LEGACY} — no active tenant TBox, or an active TBox whose
 *       {@code admittedPredicates} field is {@code null}. Back-compat: all
 *       declared predicates are admitted (fail-OPEN is correct for un-governed
 *       realms; zero regression).</li>
 *   <li>{@link Kind#GOVERNED} — an active tenant TBox with a non-null admitted
 *       set. The set defines exactly which declared predicates may be referenced
 *       by rules (Slice-1/2 enforcement).</li>
 *   <li>{@link Kind#UNAVAILABLE} — the admitted-set read threw (Mongo blip /
 *       outage). The guard MUST fail CLOSED: treat as "no predicates admitted for
 *       rule references" so rule registration referencing any predicate is
 *       rejected rather than silently allowed. This is an explicit availability
 *       tradeoff (rule (re)loading for a GOVERNED realm is rejected during a
 *       Mongo outage) chosen over a security downgrade on a blip.</li>
 * </ul>
 *
 * @param kind     which of the three states this result represents
 * @param admitted the admitted-predicate set; non-null and unmodifiable ONLY for
 *                 {@link Kind#GOVERNED}, empty/unused otherwise
 */
public record AdmittedVocabularyResult(Kind kind, Set<String> admitted) {

    public enum Kind { LEGACY, GOVERNED, UNAVAILABLE }

    private static final AdmittedVocabularyResult LEGACY_INSTANCE =
            new AdmittedVocabularyResult(Kind.LEGACY, Set.of());
    private static final AdmittedVocabularyResult UNAVAILABLE_INSTANCE =
            new AdmittedVocabularyResult(Kind.UNAVAILABLE, Set.of());

    /** No governed admitted set (legacy / un-governed realm): all declared admitted. */
    public static AdmittedVocabularyResult legacy() {
        return LEGACY_INSTANCE;
    }

    /** Governed realm: only {@code admitted} predicates may be referenced by rules. */
    public static AdmittedVocabularyResult governed(Set<String> admitted) {
        Set<String> safe = admitted == null ? Set.of() : Set.copyOf(admitted);
        return new AdmittedVocabularyResult(Kind.GOVERNED, safe);
    }

    /** The read threw: the guard must fail CLOSED (no predicates admitted for rules). */
    public static AdmittedVocabularyResult unavailable() {
        return UNAVAILABLE_INSTANCE;
    }

    public boolean isLegacy()      { return kind == Kind.LEGACY; }
    public boolean isGoverned()    { return kind == Kind.GOVERNED; }
    public boolean isUnavailable() { return kind == Kind.UNAVAILABLE; }

    /**
     * Back-compat projection onto the old 2-state contract: GOVERNED -&gt; the set,
     * LEGACY and UNAVAILABLE -&gt; empty. Use ONLY where a caller cannot distinguish
     * the read-failure state; the policy guard must NOT use this (it would re-open
     * the fail-OPEN downgrade). Provided so the legacy
     * {@code admittedPredicatesForCurrentRealm()} method keeps its exact behavior.
     */
    public Optional<Set<String>> asLegacyOptional() {
        return kind == Kind.GOVERNED
                ? Optional.of(Collections.unmodifiableSet(admitted))
                : Optional.empty();
    }
}
