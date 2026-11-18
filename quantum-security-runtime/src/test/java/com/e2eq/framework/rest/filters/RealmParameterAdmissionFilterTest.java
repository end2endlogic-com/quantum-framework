package com.e2eq.framework.rest.filters;

import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RequestRealmAdmission;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round-6 review: a realm taken from a path or query parameter (ensureIndexes/{realm}, seed admin,
 * identity-pack ?realm=, ...) opened a datastore before any check. The filter admits every
 * realm-naming parameter of the matched method first.
 */
class RealmParameterAdmissionFilterTest {

    @SuppressWarnings("unused")
    static class Resource {
        public void path(@PathParam("realm") String realm, @QueryParam("collectionName") String c) { }
        public void query(@QueryParam("realmRefName") String realm) { }
        public void pattern(@QueryParam("realmRegEx") String pattern) { }
    }

    private RealmParameterAdmissionFilter filter;
    private RequestRealmAdmission admission;
    private ContainerRequestContext request;
    private MultivaluedHashMap<String, String> path;
    private MultivaluedHashMap<String, String> query;

    @BeforeEach
    void setUp() {
        filter = new RealmParameterAdmissionFilter();
        admission = mock(RequestRealmAdmission.class);
        when(admission.admit(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            if (name.equalsIgnoreCase("acme-P1")) return "acme-P1";
            throw RemotePrincipalResolutionException.of(
                    com.e2eq.framework.security.principal.PrincipalResolutionError.REALM_NOT_AUTHORIZED, "x");
        });
        filter.admission = admission;
        request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        path = new MultivaluedHashMap<>();
        query = new MultivaluedHashMap<>();
        when(uri.getPathParameters()).thenReturn(path);
        when(uri.getQueryParameters()).thenReturn(query);
        when(request.getUriInfo()).thenReturn(uri);
    }

    private void matched(String method) throws Exception {
        ResourceInfo info = mock(ResourceInfo.class);
        for (var m : Resource.class.getMethods()) {
            if (m.getName().equals(method)) when(info.getResourceMethod()).thenReturn(m);
        }
        filter.resourceInfo = info;
    }

    @Test
    void anUnregisteredPathRealmIsRefusedBeforeTheResourceRuns() throws Exception {
        matched("path");
        path.add("realm", "zz-unreg-1");
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter.filter(request));
        assertEquals("REALM_NOT_AUTHORIZED", refused.code());
    }

    @Test
    void aRegisteredQueryRealmPassesButAnotherCaseIsRefused() throws Exception {
        matched("query");
        query.add("realmRefName", "acme-P1");
        assertDoesNotThrow(() -> filter.filter(request));
        query.putSingle("realmRefName", "acme-p1");
        assertThrows(RemotePrincipalResolutionException.class, () -> filter.filter(request));
    }

    @Test
    void aRealmPatternIsNotARealmAndAnAbsentParameterIsNotChecked() throws Exception {
        matched("pattern");
        query.add("realmRegEx", ".*");
        assertDoesNotThrow(() -> filter.filter(request));
        matched("path");
        assertDoesNotThrow(() -> filter.filter(request));
        verify(admission, never()).admit(anyString());
        assertTrue(RealmParameterAdmissionFilter.namesRealm("realmId"));
        assertFalse(RealmParameterAdmissionFilter.namesRealm("collectionName"));
    }
}
