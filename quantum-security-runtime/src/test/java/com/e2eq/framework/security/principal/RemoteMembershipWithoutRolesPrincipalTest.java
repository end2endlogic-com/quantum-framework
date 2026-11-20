package com.e2eq.framework.security.principal;

import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.model.UserRealmRoleEntry;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.system.remote.ControlPlaneRealmMapper;
import com.e2eq.framework.system.remote.RemoteMembershipClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A service that reads memberships from the control plane (remote mode) resolves the principal of
 * a user whose only realm membership grants no roles, the shape an outside invite that names only
 * an application writes. The control plane's stored row carries no roles (Morphia drops the empty
 * list, older rows hold null); its realms-for-user response must still satisfy the contract, which
 * requires {@code roles}, or the receiving service's principal finalization fails and every
 * downstream call (for example the realm read a tenant selection makes) fails with it.
 */
class RemoteMembershipWithoutRolesPrincipalTest {

    private static final String USER = "outsider@example.test";
    private static final String REALM = "acme-D1";

    @Test
    void aUserWhoseOnlyMembershipHasNoRolesResolvesThroughTheRemoteDirectory() throws Exception {
        PrincipalFixture world = new PrincipalFixture();
        var credential = world.credential(USER, "sub-outsider", "acme-D2", "acme-two", "user");
        credential.setAuthorizedRealms(List.of(PrincipalFixture.realmEntry(REALM)));

        // The control plane's stored row: no roles, one application, active.
        UserRealmRole stored = new UserRealmRole(USER, "sub-outsider", REALM, null, List.of(PrincipalFixture.APP),
            null, List.of("acme"), null, null, UserRealmRole.STATUS_ACTIVE);
        stored.setRefName("user-realm-role:" + USER + ":" + REALM);

        // Control-plane side: the realms-for-user response is validated on return.
        UserRealmRoleEntry served = ControlPlaneRealmMapper.toEntry(stored);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertEquals(Set.of(), factory.getValidator().validate(served),
                "GET /control/users/{userId}/realms must not fail for a membership without roles");
        }
        ObjectMapper wire = new ObjectMapper();
        UserRealmRoleEntry received = wire.readValue(wire.writeValueAsBytes(served), UserRealmRoleEntry.class);

        // Receiving service: the remote membership client reads the assignment.
        List<UserRealmRole> remote = new RemoteMembershipClient(endpoint(List.of(received))).realmsForUser(USER);
        assertEquals(1, remote.size());
        assertEquals(List.of(), remote.get(0).getRoles());
        world.assignments.clear();
        world.assignments.put(USER + "|" + REALM, remote.get(0));

        PrincipalContext context = new PrincipalContext.Builder()
            .withDefaultRealm(REALM).withUserId(USER).withRoles(new String[]{"user"}).build();
        PrincipalContext finalized = world.resolver().finalizeUserPrincipal(context);
        assertEquals(USER, finalized.getUserId());
        assertEquals(REALM, finalized.getDefaultRealm());
        assertTrue(List.of(finalized.getRoles()).contains("user"), "the token's roles are kept; the membership adds none");
    }

    /**
     * A dynamic proxy rather than an anonymous class: the endpoint interface's methods carry
     * interceptor bindings, and Quarkus rejects an anonymous class in the test bean archive that
     * declares them. Only {@code realmsForUser} is exercised.
     */
    private static DefaultEndpoint endpoint(List<UserRealmRoleEntry> roles) {
        return (DefaultEndpoint) java.lang.reflect.Proxy.newProxyInstance(
            DefaultEndpoint.class.getClassLoader(), new Class<?>[]{DefaultEndpoint.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "realmsForUser" -> roles;
                case "toString" -> "RemoteMembershipWithoutRolesPrincipalTest.endpoint";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
