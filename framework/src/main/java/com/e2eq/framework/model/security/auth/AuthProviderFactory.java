
package com.e2eq.framework.model.security.auth;


import com.e2eq.framework.model.security.auth.provider.cognito.CognitoAuthProvider;
import com.e2eq.framework.model.security.auth.provider.jwtToken.CustomTokenAuthProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AuthProviderFactory {

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @ConfigProperty(name = "quarkus.oidc.enabled")
    boolean oidcEnabled;

    @ConfigProperty(name = "quarkus.smallrye-jwt.enabled")
    boolean jwtEnabled;

    @Inject
    CognitoAuthProvider cognitoAuthProvider;

    @Inject
    CustomTokenAuthProvider customTokenAuthProvider;

    public AuthProvider getAuthProvider() {
        switch (authProvider.toLowerCase()) {
            case "cognito":
                if (!oidcEnabled) {
                    throw new IllegalArgumentException("Cognito auth provider is set but quarkus.oidc.enabled=false");
                }
                return cognitoAuthProvider;
            case "custom":
                if (!jwtEnabled) {
                    throw new IllegalArgumentException("Custom token auth provider is set but quarkus.smallrye-jwt.enabled=false");
                }
                return customTokenAuthProvider;
            default:
                throw new IllegalArgumentException("Unknown auth provider: " + authProvider);
        }
    }

    public UserManagement getUserManager() {
        switch (authProvider.toLowerCase()) {
            case "cognito":
                return cognitoAuthProvider;
            case "custom":
                return customTokenAuthProvider;
            default:
                throw new IllegalArgumentException("Unknown auth provider: " + authProvider);
        }
    }

    public AuthProvider getCognitoAuthProvider() {
        return cognitoAuthProvider;
    }

    public AuthProvider getCustomTokenAuthProvider() {
        return customTokenAuthProvider;
    }
}
