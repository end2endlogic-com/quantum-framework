package com.e2eq.framework.model.persistent.morphia;

/**
 * Wrapper to force a resolver-provided value to be treated as a plain String
 * (no heuristic type coercion). When the QueryToFilterListener sees a
 * StringLiteral in a collection (e.g., for an IN clause), it unwraps it to the
 * raw string value and skips ObjectId/number/date coercions.
 */
public final class StringLiteral {
    private final String value;

    public StringLiteral(String value) {
        this.value = value;
    }

    public static StringLiteral of(String value) {
        return new StringLiteral(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
