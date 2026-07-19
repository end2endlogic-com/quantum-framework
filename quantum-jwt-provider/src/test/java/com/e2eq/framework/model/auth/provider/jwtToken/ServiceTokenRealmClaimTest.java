package com.e2eq.framework.model.auth.provider.jwtToken;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service tokens intended for tenant-plane calls must carry a signed realm
 * claim: SecurityFilter's delegated-claims path fails closed on a missing
 * realm, so a realm-blind service token is rejected with 403 by every data
 * plane running delegated validation (the Install-callback incident).
 */
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
    void realmScopedServiceTokenCarriesRealmClaim() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");

        String jwt = TokenUtils.generateUserToken(
                "svc-install-1", null, Set.of("helixorq-system", "system"),
                "system-helixorq-com", null, null, null,
                TokenUtils.expiresAt(3600), "https://auth.example.com");

        String claims = payloadJson(jwt);
        assertTrue(claims.contains("\"realm\":\"system-helixorq-com\""), claims);
        assertFalse(claims.contains("\"tenantId\""), claims);
    }

    @Test
    void realmBlindServiceTokenOmitsRealmClaim() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");

        String jwt = TokenUtils.generateUserToken(
                "svc-legacy-1", Set.of("admin"),
                TokenUtils.expiresAt(3600), "https://auth.example.com");

        assertFalse(payloadJson(jwt).contains("\"realm\""), payloadJson(jwt));
    }
}
