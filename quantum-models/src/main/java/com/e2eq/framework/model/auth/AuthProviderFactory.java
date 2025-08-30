
package com.e2eq.framework.model.auth;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AuthProviderFactory {

    @ConfigProperty(name = "auth.provider")
    String configuredAuthProvider;

    @Inject
    Instance<AuthProvider> authProviders;

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

}
