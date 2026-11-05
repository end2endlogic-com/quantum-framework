package com.e2eq.framework.security.session;

/**
 * Optional, explicitly selected application policy. Proposes a realm; never grants access.
 * Implementations must be deterministic, side-effect-free, and must not perform I/O.
 * The framework retains identity validation, realm membership and application/tenant admission.
 */
public interface SessionSelectionPolicy {
    String id();
    String version();
    Selection select(Input input);

    /** Verified identity metadata and untrusted requested realm. No bearer credentials. */
    record Input(String issuer, String subject, String signedRealm, String requestedRealm,
                 String configuredRealm, String applicationId) { }

    /** Null realm means use the principal's existing realm, not unrestricted access. */
    record Selection(String realm) { }
}
