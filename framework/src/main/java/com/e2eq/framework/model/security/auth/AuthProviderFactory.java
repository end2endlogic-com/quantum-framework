
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

    @Inject
    CognitoAuthProvider cognitoAuthProvider;

    @Inject
    CustomTokenAuthProvider customTokenAuthProvider;

    public AuthProvider getAuthProvider() {
        switch (authProvider.toLowerCase()) {
            case "cognito":
                return cognitoAuthProvider;
            case "custom":
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
