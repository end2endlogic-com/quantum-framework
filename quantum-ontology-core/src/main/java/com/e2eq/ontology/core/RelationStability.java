package com.e2eq.ontology.core;

import java.util.Locale;

/**
 * Whether consumers may bind to a relation (the ontology relation contract, P1).
 *
 * <p>A {@link #STABLE} relation is a contract: admission refuses to remove, demote, narrow or
 * re-derive it without a pack version bump ({@link StableRelationDiff}), and only stable relations
 * may be bound by consumers ({@link StableRelationGuard}). Every relation is {@link #INTERNAL}
 * unless a pack opts in, so existing packs behave and hash exactly as before.
 */
public enum RelationStability {
    STABLE("stable"),
    INTERNAL("internal");

    private final String wireValue;

    RelationStability(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Lowercase pack/wire spelling ({@code stable} or {@code internal}). */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Parse an authored {@code stability} value for property {@code propertyName}. Absent or blank
     * means {@link #INTERNAL}; matching is case-insensitive. Any other value fails: it never falls
     * back to internal silently.
     *
     * @throws IllegalArgumentException with the shared contract message for an unknown value
     */
    public static RelationStability parse(String propertyName, Object value) {
        if (value == null) return INTERNAL;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return INTERNAL;
        String normalized = text.toLowerCase(Locale.ROOT);
        for (RelationStability s : values()) {
            if (s.wireValue.equals(normalized)) return s;
        }
        throw new IllegalArgumentException("Property '" + propertyName + "' has unknown stability '" + value
                + "': use stable or internal");
    }
}
