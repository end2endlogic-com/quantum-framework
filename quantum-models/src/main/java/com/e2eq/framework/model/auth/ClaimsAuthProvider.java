package com.e2eq.framework.model.auth;

/**
 * Optional SPI for auth plugins that prefer to return provider-neutral claims.
 * The framework will assemble the canonical SecurityIdentity from these claims.
 */
public interface ClaimsAuthProvider extends AuthProvider {

    /**
     * Validate the token and return provider-neutral claims (subject, roles, attributes).
     */
    ProviderClaims validateTokenToClaims(String token);
}
