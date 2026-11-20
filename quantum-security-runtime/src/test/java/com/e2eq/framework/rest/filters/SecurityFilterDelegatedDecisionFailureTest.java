package com.e2eq.framework.rest.filters;

import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A delegated authorization Auth refused or could not give keeps its typed status and code. */
class SecurityFilterDelegatedDecisionFailureTest {

    @Test
    void authUnreachableIs503() {
        Response response = SecurityFilter.preAuthorizationFailure(
                RemotePrincipalResolutionException.unavailable("connection refused"));
        assertEquals(503, response.getStatus());
        assertEquals(Map.of("code", RemotePrincipalResolutionException.UNAVAILABLE,
                "message", "The identity could not be resolved; try again later"), response.getEntity());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
    }

    @Test
    void aRevokedGrantKeepsAuthsRefusal() {
        Response response = SecurityFilter.preAuthorizationFailure(new RuntimeException("wrapped",
                new RemotePrincipalResolutionException(403, "APPLICATION_NOT_GRANTED",
                        "The identity is not granted this application", "detail for logs")));
        assertEquals(403, response.getStatus());
        assertEquals(Map.of("code", "APPLICATION_NOT_GRANTED",
                "message", "The identity is not granted this application"), response.getEntity());
    }

    @Test
    void anythingElseStaysAGeneric500() {
        Response response = SecurityFilter.preAuthorizationFailure(new IllegalStateException("boom"));
        assertEquals(500, response.getStatus());
        assertEquals("AUTHORIZATION_CHECK_FAILED", ((Map<?, ?>) response.getEntity()).get("code"));
    }
}
