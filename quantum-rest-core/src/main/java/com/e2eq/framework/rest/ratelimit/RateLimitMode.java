package com.e2eq.framework.rest.ratelimit;

import java.util.Locale;

/** Activation semantics for the Quantum REST rate limiter. */
public enum RateLimitMode {
    OFF,
    MONITOR,
    ENFORCE;

    static RateLimitMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid rate-limit mode '" + value + "'; expected OFF, MONITOR, or ENFORCE",
                    exception);
        }
    }
}
