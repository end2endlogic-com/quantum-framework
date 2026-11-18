package com.e2eq.framework.rest.filters;

import com.e2eq.framework.annotations.RealmName;
import com.e2eq.framework.security.principal.PrincipalResolutionError;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RequestRealmAdmission;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Round-7 item 3: a realm under another parameter name, marked {@link RealmName}, is admitted too. */
class RealmNameParameterAdmissionTest {

    @SuppressWarnings("unused")
    static class Directory {
        public void find(@PathParam("refName") @RealmName String refName) { }
        public void register(@PathParam("refName") @RealmName(registered = false) String refName) { }
        public void other(@PathParam("refName") String refName) { }
    }

    private RealmParameterAdmissionFilter filter;
    private ContainerRequestContext request;
    private MultivaluedHashMap<String, String> path;

    @BeforeEach
    void setUp() {
        filter = new RealmParameterAdmissionFilter();
        RequestRealmAdmission admission = mock(RequestRealmAdmission.class);
        when(admission.admit(anyString())).thenAnswer(call -> {
            if ("acme-P1".equals(call.getArgument(0))) return "acme-P1";
            throw RemotePrincipalResolutionException.of(PrincipalResolutionError.REALM_NOT_AUTHORIZED, "x");
        });
        when(admission.admitShape(anyString())).thenCallRealMethod();
        when(admission.reserved(anyString())).thenCallRealMethod();
        filter.admission = admission;
        request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        path = new MultivaluedHashMap<>();
        when(uri.getPathParameters()).thenReturn(path);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(request.getUriInfo()).thenReturn(uri);
    }

    private void matched(String name) {
        ResourceInfo info = mock(ResourceInfo.class);
        for (var m : Directory.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) when(info.getResourceMethod()).thenReturn(m);
        }
        when(info.getResourceClass()).thenAnswer(call -> Directory.class);
        filter.resourceInfo = info;
    }

    @Test
    void aMarkedRefNameMustBeRegistered() {
        matched("find");
        path.putSingle("refName", "zz-unreg");
        assertThrows(RemotePrincipalResolutionException.class, () -> filter.filter(request));
        path.putSingle("refName", "acme-P1");
        assertDoesNotThrow(() -> filter.filter(request));
    }

    @Test
    void aRegistrationChecksOnlyTheShapeAndReservedNames() {
        matched("register");
        path.putSingle("refName", "zz-new-realm");
        assertDoesNotThrow(() -> filter.filter(request));
        for (String bad : new String[] {"admin", "zz-new\t", " zz-new", "zz.new"}) {
            path.putSingle("refName", bad);
            assertThrows(RemotePrincipalResolutionException.class, () -> filter.filter(request), "'" + bad + "'");
        }
    }

    @Test
    void anUnmarkedRefNameIsNotARealm() {
        matched("other");
        path.putSingle("refName", "zz-anything");
        assertDoesNotThrow(() -> filter.filter(request));
    }
}
