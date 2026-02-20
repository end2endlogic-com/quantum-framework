package com.e2eq.framework.security;

import com.e2eq.framework.model.auth.*;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Facade used by resources/filters to obtain a canonical SecurityIdentity regardless of provider.
 * Prefer using this over calling provider.validateAccessToken directly.
 */
@ApplicationScoped
public class CanonicalIdentityService {

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    IdentityAssembler identityAssembler;

    @Inject
    SecurityIdentityNormalizer identityNormalizer;

    public SecurityIdentity validateAccessTokenCanonical(String token) {
        AuthProvider provider = authProviderFactory.getAuthProvider();
        return validateWithProvider(provider, token);
    }

    /**
     * Validate a token using the provider that matches the given issuer.
     * Used for multi-provider setups where tokens from different issuers
     * must be routed to the correct provider.
     */
    public SecurityIdentity validateAccessTokenCanonical(String token, String issuer) {
        AuthProvider provider = authProviderFactory.getProviderForIssuer(issuer);
        return validateWithProvider(provider, token);
    }

    private SecurityIdentity validateWithProvider(AuthProvider provider, String token) {
        if (provider instanceof ClaimsAuthProvider claimsProvider) {
            ProviderClaims claims = claimsProvider.validateTokenToClaims(token);
            return identityAssembler.assemble(claims);
        } else {
            SecurityIdentity legacy = provider.validateAccessToken(token);
            return identityNormalizer.normalize(legacy);
        }
    }
}
