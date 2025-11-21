
package com.e2eq.framework.model.auth;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.security.identity.SecurityIdentity;

@ApplicationScoped
public class AuthProviderFactory {

    @ConfigProperty(name = "auth.provider")
    String configuredAuthProvider;

    @Inject
    Instance<AuthProvider> authProviders;

    // Note: Do NOT inject framework services here to avoid cross-module coupling.

    public AuthProvider getAuthProvider() {
        for (AuthProvider authProvider : authProviders) {
            if (authProvider.getName().toLowerCase().equals(configuredAuthProvider.toLowerCase())) {
                return authProvider;
            }
        }

          throw new IllegalArgumentException(String.format( "Authprovider:%s was not found ", configuredAuthProvider));
        }


    public UserManagement getUserManager() {
            AuthProvider authProvider = this.getAuthProvider();
            if (authProvider instanceof UserManagement) {
                return (UserManagement) authProvider;
            } else {
                throw new IllegalArgumentException(String.format("Authprovider:%s does not implement UserManagement", configuredAuthProvider));
            }

    }

    // Canonical validation is implemented in quantum-framework CanonicalIdentityService to keep module boundaries clean.

}
