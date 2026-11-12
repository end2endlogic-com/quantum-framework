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
        RemotePrincipalRequest a = new RemotePrincipalRequest("token-a", "iss", "sub", "app", "r", "t", null, null);
        RemotePrincipalRequest b = new RemotePrincipalRequest("token-b", "iss", "sub", "app", "r", "t", null, null);
        assertEquals(a.cacheKey(), b.cacheKey());
        assertNotEquals(a.cacheKey(),
                new RemotePrincipalRequest("token-a", "iss", "sub", "app", "r", "other", null, null).cacheKey());
        assertNotEquals(a.cacheKey(), new RemotePrincipalRequest("token-a", "iss", "sub", "app", "r", "t",
                new PrincipalRef("u", null), null).cacheKey());
        assertNotEquals(a.cacheKey(), new RemotePrincipalRequest("token-a", "iss", "sub", "app", "r", "t",
                null, new PrincipalRef("u", null)).cacheKey());
        assertNotEquals(a.cacheKey(),
                new RemotePrincipalRequest("token-a", "other-iss", "sub", "app", "r", "t", null, null).cacheKey());
    }

    @Test
    void toStringNeverPrintsTheBearer() {
        RemotePrincipalRequest request = new RemotePrincipalRequest("secret-bearer", "iss", "sub", "app",
                null, null, null, null);
        assertFalse(request.toString().contains("secret-bearer"));
    }

    @Test
    void blankSelectionsAreAbsent() {
        RemotePrincipalRequest request = RemotePrincipalRequest.fromHeaders("b", "iss", "sub", "app",
                Map.of("X-Realm", " ", "X-Impersonate-UserId", "")::get);
        assertNull(request.realm());
        assertNull(request.impersonate());
    }

    @Test
    void anApplicationIdIsRequired() {
        assertThrows(NullPointerException.class,
                () -> new RemotePrincipalRequest("b", "iss", "sub", " ", null, null, null, null));
    }
}
