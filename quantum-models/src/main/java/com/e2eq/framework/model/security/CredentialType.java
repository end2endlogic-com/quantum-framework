package com.e2eq.framework.model.security;

/**
 * Distinguishes different kinds of credentials associated with a user profile.
 * Each credential carries its own issuer, roles, and auth-provider association.
 */
public enum CredentialType {
    /** Traditional userId/password credential used for interactive login. */
    PASSWORD,

    /**
     * Mailbox possession verified by an authority-owned single-use challenge. The holder may
     * also set a password, which then admits password login as a second method; email login
     * stays available. A PASSWORD credential never gains email login.
     */
    EMAIL,

    /** Long-lived token credential for MCP servers, service accounts, and API integrations. */
    SERVICE_TOKEN,

    /** Future: API-key-based credential. */
    API_KEY,

    /** Future: OAuth/external-provider credential. */
    OAUTH
}
