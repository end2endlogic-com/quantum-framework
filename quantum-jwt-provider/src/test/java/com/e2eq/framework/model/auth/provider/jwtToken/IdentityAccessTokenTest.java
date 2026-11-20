package com.e2eq.framework.model.auth.provider.jwtToken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token contract v2 user access tokens ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 1, rollout
 * step 4): who the caller is and nothing else, minted only when {@code quantum.auth.token-contract=v2}.
 */
class IdentityAccessTokenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REMOVED = Set.of(
            "groups", "roles", "realm", "realmRegEx", "tenant", "tenantId", "orgRefName", "accountNum", "aud", "azp");

    @AfterEach
    void reset() {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
    }

    private static JsonNode payload(String jwt) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private static Set<String> names(JsonNode claims) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = claims.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    @Test
    void carriesIdentityClaimsOnly() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
        long exp = TokenUtils.expiresAt(900);

        JsonNode claims = payload(TokenUtils.generateIdentityAccessToken(
                "sub-alice", "alice@acme", null, exp, "https://auth.example.com"));

        assertEquals(new TreeSet<>(Set.of("iss", "sub", "userId", "iat", "exp", "jti", "scope", "token_type")),
                names(claims), claims.toString());
        assertEquals("https://auth.example.com", claims.get("iss").asText());
        assertEquals("sub-alice", claims.get("sub").asText());
        assertEquals("alice@acme", claims.get("userId").asText());
        assertEquals(exp, claims.get("exp").asLong());
        assertEquals("access", claims.get("token_type").asText());
        assertEquals(TokenUtils.AUTH_SCOPE, claims.get("scope").asText(),
                "scope tells an access token from a refresh token, as validateAccessToken requires");
    }

    @Test
    void carriesTheAuthenticationTimeWhenKnown() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
        JsonNode claims = payload(TokenUtils.generateIdentityAccessToken(
                "sub-alice", "alice@acme", 1_700_000_000L, TokenUtils.expiresAt(900), "https://auth.example.com"));
        assertEquals(1_700_000_000L, claims.get(TokenUtils.AUTH_TIME_CLAIM).asLong());
        assertTrue(names(claims).stream().noneMatch(REMOVED::contains), claims.toString());
    }

    @Test
    void eachTokenHasItsOwnIdAndTheIdIsNotTheSubject() throws Exception {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
        long exp = TokenUtils.expiresAt(900);
        String first = payload(TokenUtils.generateIdentityAccessToken("s", "u", null, exp, "iss")).get("jti").asText();
        String second = payload(TokenUtils.generateIdentityAccessToken("s", "u", null, exp, "iss")).get("jti").asText();
        assertNotEquals(first, second);
        // A service token v2 is recognised by jti == sub; a user token must never look like one.
        assertNotEquals("s", first);
    }

    @Test
    void refusesAMissingIdentity() {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
        long exp = TokenUtils.expiresAt(900);
        assertThrows(ValidationException.class, () -> TokenUtils.generateIdentityAccessToken(" ", "u", null, exp, "iss"));
        assertThrows(ValidationException.class, () -> TokenUtils.generateIdentityAccessToken("s", null, null, exp, "iss"));
        assertThrows(ValidationException.class, () -> TokenUtils.generateIdentityAccessToken("s", "u", null, exp, ""));
    }

    @Test
    void tokenContractDefaultsToV1AndRefusesAnythingElseThanV1OrV2() {
        assertEquals(TokenContract.V1, TokenContract.parse(null));
        assertEquals(TokenContract.V1, TokenContract.parse(" "));
        assertEquals(TokenContract.V1, TokenContract.parse("v1"));
        assertEquals(TokenContract.V2, TokenContract.parse(" V2 "));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> TokenContract.parse("slim"));
        assertTrue(refused.getMessage().startsWith("TOKEN_CONTRACT_INVALID"), refused.getMessage());
    }

    @Test
    void theProviderMintsV1UnlessConfiguredAndFailsAtStartupOnAnInvalidContract() {
        CustomTokenAuthProvider provider = new CustomTokenAuthProvider();
        assertEquals(TokenContract.V1, provider.tokenContract(), "unset keeps deploy a no-op");
        provider.tokenContract = Optional.of("v2");
        assertEquals(TokenContract.V2, provider.tokenContract());
        provider.tokenContract = Optional.of("v3");
        assertThrows(IllegalStateException.class, provider::validateTokenContract);
    }
}
