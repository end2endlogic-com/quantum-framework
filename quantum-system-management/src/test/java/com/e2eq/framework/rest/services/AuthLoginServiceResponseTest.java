package com.e2eq.framework.rest.services;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.rest.models.AuthResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthLoginServiceResponseTest {

    @Test
    void aLoginAnswerNeverCarriesTheProvidersDatabaseUrl() throws Exception {
        AuthProvider provider = new AuthProvider() {
            @Override public SecurityIdentity validateAccessToken(String token) { return null; }
            @Override public String getName() { return "test"; }
            @Override public LoginResponse login(String userId, String password) { return null; }
            @Override public LoginResponse refreshTokens(String refreshToken) { return null; }
        };
        // A provider that still hands back a connection string must not get it to the caller.
        AuthProvider.LoginResponse login = new AuthProvider.LoginResponse(true,
            new AuthProvider.LoginPositiveResponse("someone", null, Set.of("user"), List.of(),
                "access", "refresh", 123L, "mongodb+srv://user:secret@cluster.example/db", null));

        AuthResponse response = new AuthLoginService().toAuthResponse(provider, login, null);

        assertEquals("access", response.getAccess_token());
        assertNull(response.getMongodburl());
        String json = new ObjectMapper().writeValueAsString(response);
        assertFalse(json.contains("mongodburl"), json);
        assertFalse(json.contains("secret@"), json);
    }
}
