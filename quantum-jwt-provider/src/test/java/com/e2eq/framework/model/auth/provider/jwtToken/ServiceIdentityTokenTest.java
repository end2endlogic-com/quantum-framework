package com.e2eq.framework.model.auth.provider.jwtToken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service tokens v2 ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} sections 1 and 4): the token says who
 * the service is and nothing else.
 */
class ServiceIdentityTokenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void reset() {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
    }

    private static JsonNode payload(String jwt) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    @Test
    void carriesIdentityClaimsOnly() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");

        JsonNode claims = payload(TokenUtils.generateServiceIdentityToken(
                "5b0c", "svc-5b0c", "5b0c", "https://auth.example.com"));

        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = claims.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        assertEquals(new TreeSet<>(Set.of("iss", "sub", "userId", "iat", "exp", "jti", "scope", "token_type")),
                names, claims.toString());
        assertEquals("5b0c", claims.get("sub").asText());
        assertEquals("svc-5b0c", claims.get("userId").asText());
        assertEquals("5b0c", claims.get("jti").asText());
        assertEquals("service", claims.get("token_type").asText());
        assertEquals(TokenUtils.AUTH_SCOPE, claims.get("scope").asText());
    }

    @Test
    void doesNotExpireWithinTheLifetimeOfAService() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");

        JsonNode claims = payload(TokenUtils.generateServiceIdentityToken(
                "5b0c", "svc-5b0c", "5b0c", "https://auth.example.com"));

        long lifetime = claims.get("exp").asLong() - claims.get("iat").asLong();
        assertEquals(TokenUtils.SERVICE_IDENTITY_LIFETIME_SECONDS, lifetime);
        assertTrue(lifetime >= 99L * 365 * 24 * 60 * 60, "lifetime " + lifetime);
    }

    @Test
    void refusesAMissingIdentity() {
        assertThrows(ValidationException.class,
                () -> TokenUtils.generateServiceIdentityToken(" ", "svc", "c", "https://auth.example.com"));
        assertThrows(ValidationException.class,
                () -> TokenUtils.generateServiceIdentityToken("s", null, "c", "https://auth.example.com"));
        assertThrows(ValidationException.class,
                () -> TokenUtils.generateServiceIdentityToken("s", "svc", "", "https://auth.example.com"));
        assertThrows(ValidationException.class,
                () -> TokenUtils.generateServiceIdentityToken("s", "svc", "c", null));
    }
}
