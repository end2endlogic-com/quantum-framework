package com.e2eq.framework.model.auth.provider.jwtToken;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the service-passport contract: signer + service identity, no resource binding. */
class ServiceTokenRealmClaimTest {

    @AfterEach
    void reset() {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
    }

    private static String payloadJson(String jwt) {
        String[] parts = jwt.split("\\.");
        return new String(Base64.getUrlDecoder().decode(parts[1]));
    }

    @Test
    void serviceTokenCarriesServiceDiscriminator() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");

        String jwt = TokenUtils.generateServiceToken(
                "svc-helixor-di", Set.of("service", "system"),
                TokenUtils.expiresAt(3600), "https://auth.example.com");

        String claims = payloadJson(jwt);
        assertTrue(claims.contains("\"token_type\":\"service\""), claims);
        assertTrue(claims.contains("\"sub\":\"svc-helixor-di\""), claims);
    }

    @Test
    void serviceTokenCarriesNoApplicationAudienceOrRealm() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");

        String jwt = TokenUtils.generateServiceToken(
                "svc-helixor-di", Set.of("service"),
                TokenUtils.expiresAt(3600), "https://auth.example.com");

        String claims = payloadJson(jwt);
        assertFalse(claims.contains("\"aud\""), claims);
        assertFalse(claims.contains("\"realm\""), claims);
        assertFalse(claims.contains("\"azp\""), claims);
    }

    @Test
    void applicationScopedServiceTokenCarriesAuthorizedPartyWithoutAudience() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");

        String jwt = TokenUtils.generateServiceToken(
                "svc-helixor-code", Set.of("service", "system"), "helixor-code",
                TokenUtils.expiresAt(3600), "https://auth.example.com");

        String claims = payloadJson(jwt);
        assertTrue(claims.contains("\"azp\":\"helixor-code\""), claims);
        assertFalse(claims.contains("\"aud\""), claims);
        assertFalse(claims.contains("\"realm\""), claims);
    }
}
