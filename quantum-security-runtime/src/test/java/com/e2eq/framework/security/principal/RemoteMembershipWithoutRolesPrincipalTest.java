package com.e2eq.framework.security.principal;

import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.controlplane.model.RealmMembershipEntry;
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

    private static DefaultEndpoint endpoint(List<UserRealmRoleEntry> roles) {
        return new DefaultEndpoint() {
            @Override public RealmCatalogEntry findRealmByEmailDomain(String e) { return null; }
            @Override public RealmCatalogEntry findRealmByRefName(String r) { return null; }
            @Override public RealmCatalogEntry registerRealm(String refName, RealmCatalogEntry b) { return b; }
            @Override public List<RealmMembershipEntry> membersOfRealm(String refName) { return List.of(); }
            @Override public RealmMembershipEntry upsertRealmMembership(String r, String o, RealmMembershipEntry b) { return b; }
            @Override public List<UserRealmRoleEntry> realmsForUser(String userId) { return roles; }
            @Override public UserRealmRoleEntry upsertUserRealmRole(String u, String r, UserRealmRoleEntry b) { return b; }
        };
    }
}
