package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.morphia.DirectoryOwnership;
import com.e2eq.framework.rest.core.BaseResource;
import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Under a remote directory the realm, membership and user realm role endpoints do not serve this
 * service's stale copy: all are refused with DIRECTORY_REMOTE except the grant endpoints, which
 * read and write at the owner. Under a local directory nothing is refused.
 */
class DirectoryRemoteRestFilterTest {

    @Test
    void aRemoteDirectoryRefusesTheCopysEndpointsAndInheritedCrud() {
        Response listed = filter(true, RealmResource.class, method(BaseResource.class, "getCount"));
        assertNotNull(listed, "a generic read of the realm catalog is refused");
        assertEquals(409, listed.getStatus());
        assertEquals("DIRECTORY_REMOTE", ((RestError) listed.getEntity()).getErrorCode());

        assertNotNull(filter(true, RealmResource.class, method(RealmResource.class, "accessible")));
        assertNotNull(filter(true, RealmTenantMembershipResource.class, method(BaseResource.class, "byId")));
        assertNotNull(filter(true, UserRealmRoleResource.class, method(UserRealmRoleResource.class, "listAssignedUsers")));
        assertNotNull(filter(true, UserRealmRoleResource.class, method(UserRealmRoleResource.class, "evaluateApplicationAccess")),
            "evaluation reads the local credential and realm catalog, so it is refused");
    }

    @Test
    void theGrantEndpointsAreServedAtTheOwner() {
        for (String name : new String[] {"getApplicationGrant", "setApplicationGrant", "clearApplicationGrant",
                "getTenantGrant", "setTenantGrant"}) {
            assertNull(filter(true, UserRealmRoleResource.class, method(UserRealmRoleResource.class, name)), name);
        }
    }

    @Test
    void otherResourcesAndALocalDirectoryAreUntouched() {
        assertNull(filter(true, PolicyResource.class, method(BaseResource.class, "getCount")));
        assertNull(filter(false, RealmResource.class, method(BaseResource.class, "getCount")));
        assertNull(filter(false, UserRealmRoleResource.class, method(UserRealmRoleResource.class, "listAssignedUsers")));
    }

    /**
     * The older catalog-only setting: the owner holds the realm catalog, this service still holds
     * the memberships. The catalog endpoints stop serving the stale copy; the membership and user
     * realm role endpoints keep serving this service's own records.
     */
    @Test
    void aCatalogOnlyRemoteClosesTheCatalogEndpointsAlone() {
        Response catalog = filter(true, false, RealmResource.class, method(BaseResource.class, "getCount"));
        assertNotNull(catalog, "a read of the stale realm catalog is refused");
        assertEquals(409, catalog.getStatus());
        assertNotNull(filter(true, false, RealmResource.class, method(RealmResource.class, "accessible")));

        assertNull(filter(true, false, RealmTenantMembershipResource.class, method(BaseResource.class, "byId")));
        assertNull(filter(true, false, UserRealmRoleResource.class,
            method(UserRealmRoleResource.class, "listAssignedUsers")));
    }

    /** Runs the filter for one endpoint with the catalog and memberships routed together. */
    private static Response filter(boolean remote, Class<?> resourceClass, Method method) {
        return filter(remote, remote, resourceClass, method);
    }

    /** Runs the filter for one endpoint; the response it aborted with, or null when it let it through. */
    private static Response filter(boolean catalogRemote, boolean membershipsRemote, Class<?> resourceClass,
                                   Method method) {
        DirectoryRemoteRestFilter filter = new DirectoryRemoteRestFilter();
        filter.directoryOwnership = new DirectoryOwnership() {
            @Override
            public boolean catalogOwnedHere() {
                return !catalogRemote;
            }

            @Override
            public boolean membershipsOwnedHere() {
                return !membershipsRemote;
            }
        };
        filter.resourceInfo = new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return method;
            }

            @Override
            public Class<?> getResourceClass() {
                return resourceClass;
            }
        };
        AtomicReference<Response> aborted = new AtomicReference<>();
        ContainerRequestContext request = (ContainerRequestContext) Proxy.newProxyInstance(
            DirectoryRemoteRestFilterTest.class.getClassLoader(), new Class<?>[] {ContainerRequestContext.class},
            (proxy, m, args) -> {
                if ("abortWith".equals(m.getName())) {
                    aborted.set((Response) args[0]);
                }
                return null;
            });
        filter.filter(request);
        return aborted.get();
    }

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError(type.getSimpleName() + " has no " + name));
    }
}
