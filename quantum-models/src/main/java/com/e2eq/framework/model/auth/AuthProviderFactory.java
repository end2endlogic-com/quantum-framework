
package com.e2eq.framework.model.auth;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AuthProviderFactory {

    @ConfigProperty(name = "auth.provider")
    String configuredAuthProviders; // supports comma-separated list (e.g., "cognito,custom")

    @Inject
    Instance<AuthProvider> authProviders;

    // Note: Do NOT inject framework services here to avoid cross-module coupling.

    /**
     * Returns the first (default) configured provider.
     * Backward compatible — single-value config works as before.
     */
    public AuthProvider getAuthProvider() {
        String firstName = configuredAuthProviders.split(",")[0].trim();
        return getProviderByName(firstName);
    }

    /**
     * Look up a specific provider by name.
     */
    public AuthProvider getProviderByName(String name) {
        for (AuthProvider authProvider : authProviders) {
            if (authProvider.getName().equalsIgnoreCase(name.trim())) {
                return authProvider;
            }
        }
        throw new IllegalArgumentException(String.format("AuthProvider:%s was not found", name));
    }

    /**
     * Resolve provider by JWT issuer claim. Iterates configured providers
     * and matches against their getIssuer(). Falls back to default provider.
     */
    public AuthProvider getProviderForIssuer(String issuer) {
        if (issuer == null) return getAuthProvider();

        for (String providerName : configuredAuthProviders.split(",")) {
            AuthProvider p = getProviderByName(providerName.trim());
            if (issuer.equals(p.getIssuer())) return p;
        }
        // No match — fall back to default provider
        return getAuthProvider();
    }

    public UserManagement getUserManager() {
        AuthProvider authProvider = this.getAuthProvider();
        if (authProvider instanceof UserManagement um) {
            return um;
        } else {
            throw new IllegalArgumentException(
                    String.format("AuthProvider:%s does not implement UserManagement", authProvider.getName()));
        }
    }

    public UserManagement getUserManager(String providerName) {
        AuthProvider authProvider = getProviderByName(providerName);
        if (authProvider instanceof UserManagement um) {
            return um;
        } else {
            throw new IllegalArgumentException(
                    String.format("AuthProvider:%s does not implement UserManagement", providerName));
        }
    }

    // Canonical validation is implemented in quantum-framework CanonicalIdentityService to keep module boundaries clean.

}
