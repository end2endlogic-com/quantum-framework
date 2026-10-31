package com.e2eq.framework.rest.usage;

import java.util.Locale;

/** Contract posture for REST usage governance. */
public enum UsageGovernanceMode {
    OFF,
    OBSERVE,
    ENFORCE;

    public static UsageGovernanceMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "quantum.rest.usage.mode must be OFF, OBSERVE, or ENFORCE; was '" + value + "'",
                    exception);
        }
    }
}
