package com.e2eq.framework.model.auth.provider.jwtToken;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the centralized issuer projects the principal's DomainContext
 * (realm/tenant/org/account + userId) into token claims. Without this a
 * centrally-issued token is tenant-blind and Helixor's claims_to_principal
 * would receive null realm/tenant/org.
 */
class TokenUtilsTenantClaimsTest {

    @AfterEach
    void reset() {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
    }

    private static String payloadJson(String jwt) {
        String[] parts = jwt.split("\\.");
        return new String(Base64.getUrlDecoder().decode(parts[1]));
    }

    @Test
    void tenantAwareTokenCarriesDomainContextClaims() throws Exception {
        TokenUtils.configure("file:/tmp/quantum-auth-keys/privateKey.pem", "file:/tmp/quantum-auth-keys/publicKey.pem");

        String jwt = TokenUtils.generateUserToken(
                "svc-helixor-001", "helixor-service", Set.of("admin", "service"),
                "acme-com", "acme.com", "acme", "0010000101",
                TokenUtils.expiresAt(3600), "https://auth.example.com");

        String claims = payloadJson(jwt);
        assertTrue(claims.contains("\"realm\":\"acme-com\""), claims);
        assertTrue(claims.contains("\"tenantId\":\"acme.com\""), claims);
        assertTrue(claims.contains("\"orgRefName\":\"acme\""), claims);
        assertTrue(claims.contains("\"accountNum\":\"0010000101\""), claims);
        assertTrue(claims.contains("\"userId\":\"helixor-service\""), claims);
        assertTrue(claims.contains("\"sub\":\"svc-helixor-001\""), claims);
    }

    @Test
    void legacyOverloadStaysTenantBlind() throws Exception {
        TokenUtils.configure("file:/tmp/quantum-auth-keys/privateKey.pem", "file:/tmp/quantum-auth-keys/publicKey.pem");

        String jwt = TokenUtils.generateUserToken(
                "svc-1", Set.of("admin"), TokenUtils.expiresAt(3600), "https://auth.example.com");

        String claims = payloadJson(jwt);
        // back-compat: no tenant claims when none supplied
        assertFalse(claims.contains("\"realm\""), claims);
        assertFalse(claims.contains("\"tenantId\""), claims);
    }
}
