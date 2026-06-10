package com.e2eq.framework.api.system;

/**
 * Deployment mode of a Quantum application with respect to the control-plane /
 * data-plane split (CONTROL_PLANE_SPLIT_DESIGN.md; wp1-platform-readiness.md B3;
 * wp3 tiers).
 *
 * <pre>
 *   quantum.mode=embedded   # default — the app owns its system realm: runs
 *                           # system-realm migrations, baseline identity, and
 *                           # seeds locally (today's behavior, all tiers 0/1)
 *   quantum.mode=remote     # split planes (tier 2) — the control plane owns
 *                           # the system realm; this app skips system-realm
 *                           # startup work and resolves cross-realm concerns
 *                           # through SystemDirectory remote mode (Phase C)
 * </pre>
 */
public enum QuantumMode {
    EMBEDDED,
    REMOTE;

    public static QuantumMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return EMBEDDED;
        }
        switch (value.trim().toLowerCase()) {
            case "embedded":
                return EMBEDDED;
            case "remote":
                return REMOTE;
            default:
                throw new IllegalStateException(
                    "Unknown quantum.mode '" + value + "'; expected 'embedded' or 'remote'.");
        }
    }
}
