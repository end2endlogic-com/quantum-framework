package com.e2eq.framework.rest.filters;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The tenant of a v2 access token's resolution is the caller's selection (SecurityFilter passes
 * none on an operation that selects the issuer's tenant), never the X-Tenant-Id header read again.
 */
class IdentityOnlyAccessTokensTenantTest {

    private static final Map<String, String> HEADERS = Map.of("X-Tenant-Id", "tenant-from-header");

    private static JsonWebToken token() {
        JsonWebToken token = mock(JsonWebToken.class);
        when(token.getClaim("sub")).thenReturn("sub-alice");
        when(token.getClaim("userId")).thenReturn("alice@acme");
        return token;
    }

    @Test
    void theAuthorityRequestTakesTheCallersTenantNotTheHeader() {
        var target = new IdentityOnlyAccessTokens.Target("quantum-system", true);
        assertNull(IdentityOnlyAccessTokens.request(token(), target, "pooled", null, HEADERS::get, false).tenantId());
        assertEquals("tenant-b",
                IdentityOnlyAccessTokens.request(token(), target, "pooled", "tenant-b", HEADERS::get, false).tenantId());
    }

    @Test
    void theReceiverRequestTakesTheCallersTenantNotTheHeader() {
        var target = new IdentityOnlyAccessTokens.Target("app", false);
        assertNull(IdentityOnlyAccessTokens.request(token(), target, "pooled", null, HEADERS::get, false).tenantId());
        assertEquals("tenant-b",
                IdentityOnlyAccessTokens.request(token(), target, "pooled", "tenant-b", HEADERS::get, false).tenantId());
    }
}
