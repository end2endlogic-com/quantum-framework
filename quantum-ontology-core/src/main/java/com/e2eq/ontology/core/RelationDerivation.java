package com.e2eq.ontology.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Authoring form of a property's {@code inferred} bit (the ontology relation contract, P1):
 * {@link #DERIVED} means {@code inferred = true}. The model keeps only the bit, so derivation never
 * becomes a key of its own in the canonical hash.
 */
public enum RelationDerivation {
    ASSERTED("asserted"),
    DERIVED("derived");

    private final String wireValue;

    RelationDerivation(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Lowercase pack/wire spelling, also the JSON form ({@code asserted} or {@code derived}). */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** The {@code inferred} bit this derivation stands for. */
    public boolean inferred() {
        return this == DERIVED;
    }

    public static RelationDerivation of(boolean inferred) {
        return inferred ? DERIVED : ASSERTED;
    }

    /** JSON form: the lowercase wire value (case-insensitive); unknown values fail. */
    @JsonCreator
    public static RelationDerivation fromJson(String value) {
        return parse("<json>", value);
    }

    /**
     * Parse an authored {@code derivation} value for property {@code propertyName}; matching is
     * case-insensitive. Returns null when absent or blank.
     *
     * @throws IllegalArgumentException with the shared contract message for an unknown value
     */
    public static RelationDerivation parse(String propertyName, Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        String normalized = text.toLowerCase(Locale.ROOT);
        for (RelationDerivation d : values()) {
            if (d.wireValue.equals(normalized)) return d;
        }
        throw new IllegalArgumentException("Property '" + propertyName + "' has unknown derivation '" + normalized
                + "': use asserted or derived");
    }

    /**
     * Resolve the {@code inferred} bit from an authored {@code derivation} and {@code inferred}
     * pair, either of which may be absent (null). When both are given they must agree.
     *
     * @throws IllegalArgumentException for an unknown derivation, or when the two disagree
     */
    public static boolean resolveInferred(String propertyName, Object derivation, Boolean inferred) {
        checkAuthored(propertyName, null, derivation, inferred);
        return inferredBit(propertyName, derivation, inferred);
    }

    /**
     * The {@code inferred} bit without the conflict check: an explicit {@code inferred} wins,
     * otherwise it is {@code derivation == derived}. Used where a conflict is reported separately.
     *
     * @throws IllegalArgumentException for an unknown derivation
     */
    public static boolean inferredBit(String propertyName, Object derivation, Boolean inferred) {
        RelationDerivation parsed = parse(propertyName, derivation);
        if (inferred != null) return inferred;
        return parsed != null && parsed.inferred();
    }

    /**
     * Check one property's authored contract fields in the shared order: unknown stability, then
     * unknown derivation, then a derivation/inferred conflict. Callers check properties in name order
     * so the first error matches the Python runtime.
     *
     * @throws IllegalArgumentException with the shared contract message for the first failure
     */
    public static void checkAuthored(String propertyName, Object stability, Object derivation, Boolean inferred) {
        RelationStability.parse(propertyName, stability);
        RelationDerivation parsed = parse(propertyName, derivation);
        if (parsed != null && inferred != null && inferred != parsed.inferred()) {
            throw new IllegalArgumentException("Property '" + propertyName + "' declares derivation '"
                    + parsed.wireValue + "' but inferred is " + inferred);
        }
    }
}
