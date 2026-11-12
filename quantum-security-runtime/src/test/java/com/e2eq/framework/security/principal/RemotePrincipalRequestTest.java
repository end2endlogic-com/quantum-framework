package com.e2eq.framework.security.principal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemotePrincipalRequestTest {

    @Test
    void theCacheKeyIsTheIdentityAndSelectionsNotTheBearer() {
        RemotePrincipalRequest a = request("token-a", "iss", "sub", "t", null, null, false);
        assertEquals(a.cacheKey(), request("token-b", "iss", "sub", "t", null, null, false).cacheKey());
        assertNotEquals(a.cacheKey(), request("token-a", "iss", "sub", "other", null, null, false).cacheKey());
        assertNotEquals(a.cacheKey(), request("token-a", "iss", "sub", "t",
                new PrincipalRef("u", null), null, false).cacheKey());
        assertNotEquals(a.cacheKey(), request("token-a", "iss", "sub", "t",
                null, new PrincipalRef("u", null), false).cacheKey());
        assertNotEquals(a.cacheKey(), request("token-a", "other-iss", "sub", "t", null, null, false).cacheKey());
        assertNotEquals(a.cacheKey(), request("token-a", "iss", "other-sub", "t", null, null, false).cacheKey());
        assertNotEquals(a.cacheKey(), request("token-a", "iss", "sub", "t", null, null, true).cacheKey());
    }

    @Test
    void aTokenWithoutIssuerOrSubjectIsNeverResolved() {
        for (String[] identity : new String[][]{{null, "sub"}, {"iss", null}, {" ", "sub"}, {"iss", ""}}) {
            RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                    () -> request("token", identity[0], identity[1], null, null, null, false));
            assertEquals(401, refused.status());
            assertEquals("PRINCIPAL_UNKNOWN", refused.code());
        }
        RemotePrincipalResolutionException noBearer = assertThrows(RemotePrincipalResolutionException.class,
                () -> request(null, "iss", "sub", null, null, null, false));
        assertEquals(401, noBearer.status());
    }

    @Test
    void toStringNeverPrintsTheBearer() {
        assertFalse(request("secret-bearer", "iss", "sub", null, null, null, false).toString()
                .contains("secret-bearer"));
    }

    @Test
    void blankSelectionsAreAbsent() {
        RemotePrincipalRequest request = RemotePrincipalRequest.fromHeaders("b", "iss", "sub", "app",
                Map.of("X-Realm", " ", "X-Impersonate-UserId", "")::get, false);
        assertNull(request.realm());
        assertNull(request.impersonate());
    }

    @Test
    void anApplicationIdIsRequired() {
        assertThrows(NullPointerException.class,
                () -> new RemotePrincipalRequest("b", "iss", "sub", " ", null, null, null, null, false));
    }

    private static RemotePrincipalRequest request(String bearer, String iss, String sub, String tenant,
                                                  PrincipalRef impersonate, PrincipalRef acting, boolean optional) {
        return new RemotePrincipalRequest(bearer, iss, sub, "app", "r", tenant, impersonate, acting, optional);
    }
}
