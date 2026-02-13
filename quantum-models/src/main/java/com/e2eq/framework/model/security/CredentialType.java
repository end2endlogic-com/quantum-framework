package com.e2eq.framework.model.security;

/**
 * Distinguishes different kinds of credentials associated with a user profile.
 * Each credential carries its own issuer, roles, and auth-provider association.
 */
public enum CredentialType {
    /** Traditional userId/password credential used for interactive login. */
    PASSWORD,

    /** Long-lived token credential for MCP servers, service accounts, and API integrations. */
    SERVICE_TOKEN,

    /** Future: API-key-based credential. */
    API_KEY,

    /** Future: OAuth/external-provider credential. */
    OAUTH
}
