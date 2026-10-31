package com.e2eq.framework.rest.services;

import com.e2eq.framework.model.auth.AuthProvider;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthLoginServiceRealmTest {

    @Test
    void selectedRealmIsForwardedToAuthenticationProvider() {
        AtomicReference<String> selectedRealm = new AtomicReference<>();
        AuthProvider provider = new AuthProvider() {
            @Override public SecurityIdentity validateAccessToken(String token) { return null; }
            @Override public String getName() { return "test"; }
            @Override public LoginResponse login(String userId, String password) { return failure(); }
            @Override public LoginResponse login(String userId, String password,
                                                  String applicationId, String realmId) {
                selectedRealm.set(realmId);
                return failure();
            }
            @Override public LoginResponse refreshTokens(String refreshToken) { return failure(); }
            private LoginResponse failure() {
                return new LoginResponse(false, new LoginNegativeResponse(
                    "local-test", 401, 401, "test", "test", "test", "system-com"));
            }
        };

        AuthLoginService.dispatchLogin(
            provider, "local-test", "secret", "data-integration", "field-service-com");

        assertEquals("field-service-com", selectedRealm.get());
    }
}
