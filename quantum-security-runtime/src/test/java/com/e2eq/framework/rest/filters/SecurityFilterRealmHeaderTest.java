package com.e2eq.framework.rest.filters;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.security.principal.RealmRefNames;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every principal source sees {@code X-Realm} as the registered refName: resources read the header
 * as a database name, and MongoDB refuses a database whose name differs only in case (13297).
 */
class SecurityFilterRealmHeaderTest {

    @BeforeEach
    void freshCache() {
        RealmRefNames.forget();
    }

    private static SecurityFilter filterWith(SystemDirectory directory) {
        SecurityFilter filter = new SecurityFilter();
        filter.systemDirectory = directory;
        return filter;
    }

    private static ContainerRequestContext request(MultivaluedMap<String, String> headers) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getHeaderString(anyString())).thenAnswer(call -> headers.getFirst(call.getArgument(0)));
        return request;
    }

    @Test
    void aRealmInAnotherCaseIsRewrittenToItsRegisteredRefName() {
        Realm pooled = new Realm();
        pooled.setRefName("helixor-code-P1");
        SystemDirectory directory = mock(SystemDirectory.class);
        when(directory.findRealmByRefName(anyString())).thenAnswer(call ->
                ((String) call.getArgument(0)).equalsIgnoreCase("helixor-code-P1") ? Optional.of(pooled) : Optional.empty());
        for (String sent : new String[]{"HELIXOR-CODE-p1", "helixor-code-p1", "helixor-code-P1"}) {
            MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
            headers.putSingle("X-Realm", sent);
            filterWith(directory).canonicalizeRealmHeader(request(headers));
            assertEquals("helixor-code-P1", headers.getFirst("X-Realm"), sent);
        }
    }

    @Test
    void anUnregisteredRealmIsLeftForTheTypedRefusalAndNoHeaderStaysAbsent() {
        SystemDirectory directory = mock(SystemDirectory.class);
        when(directory.findRealmByRefName(anyString())).thenReturn(Optional.empty());
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("X-Realm", "No-Such-Realm");
        filterWith(directory).canonicalizeRealmHeader(request(headers));
        assertEquals("No-Such-Realm", headers.getFirst("X-Realm"));

        MultivaluedMap<String, String> none = new MultivaluedHashMap<>();
        filterWith(directory).canonicalizeRealmHeader(request(none));
        assertNull(none.getFirst("X-Realm"));
    }

    @Test
    void aRemoteReceiverTakesTheRealmsCaseFromThePrincipalAuthResolved() {
        com.e2eq.framework.model.securityrules.PrincipalContext resolved =
                mock(com.e2eq.framework.model.securityrules.PrincipalContext.class);
        when(resolved.getDefaultRealm()).thenReturn("helixor-code-P1");
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("X-Realm", "HELIXOR-CODE-p1");
        SecurityFilter.alignRealmHeader(request(headers), resolved);
        assertEquals("helixor-code-P1", headers.getFirst("X-Realm"));

        MultivaluedMap<String, String> other = new MultivaluedHashMap<>();
        other.putSingle("X-Realm", "quantum-system");
        SecurityFilter.alignRealmHeader(request(other), resolved);
        assertEquals("quantum-system", other.getFirst("X-Realm"), "a header naming another realm is left as is");
    }

    @Test
    void aCatalogOutageIsRefusedTypedUnavailable() {
        SystemDirectory directory = mock(SystemDirectory.class);
        when(directory.findRealmByRefName(anyString())).thenThrow(new IllegalStateException("control plane down"));
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("X-Realm", "acme");
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filterWith(directory).canonicalizeRealmHeader(request(headers)));
        assertEquals(503, refused.status());
        assertEquals("PRINCIPAL_RESOLUTION_UNAVAILABLE", refused.code());
    }
}
