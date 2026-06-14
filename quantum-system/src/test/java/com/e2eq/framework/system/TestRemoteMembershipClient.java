package com.e2eq.framework.system;

import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.system.remote.RemoteMembershipClient;
import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.controlplane.model.RealmMembershipEntry;
import com.e2eq.framework.controlplane.model.UserRealmRoleEntry;
import jakarta.ws.rs.ProcessingException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * RemoteMembershipClient as a pure mapper over the SDK-generated control-plane
 * client ({@link DefaultEndpoint}) — roster + per-realm-role mapping and
 * fail-loud, driven by a stub endpoint (no HTTP).
 */
public class TestRemoteMembershipClient {

    static RealmMembershipEntry member(String org, String role) {
        RealmMembershipEntry e = new RealmMembershipEntry();
        e.setRealmRefName("fulfillment-demo-com");
        e.setOrganizationRefName(org);
        e.setMembershipRole(role);
        return e;
    }

    static UserRealmRoleEntry role(String realm, List<String> roles, String status) {
        UserRealmRoleEntry e = new UserRealmRoleEntry();
        e.setUserId("pat@acme.com");
        e.setRealmRefName(realm);
        e.setRoles(roles);
        e.setStatus(status);
        return e;
    }

    static DefaultEndpoint endpoint(List<RealmMembershipEntry> members, List<UserRealmRoleEntry> roles) {
        return new DefaultEndpoint() {
            @Override public RealmCatalogEntry findRealmByEmailDomain(String e) { return null; }
            @Override public RealmCatalogEntry findRealmByRefName(String r) { return null; }
            @Override public RealmCatalogEntry registerRealm(RealmCatalogEntry b) { return b; }
            @Override public List<RealmMembershipEntry> membersOfRealm(String refName) { return members; }
            @Override public List<UserRealmRoleEntry> realmsForUser(String userId) { return roles; }
        };
    }

    @Test
    public void mapsRealmMembers() {
        RemoteMembershipClient client = new RemoteMembershipClient(endpoint(
            List.of(member("acme.com", "owner"), member("fastfreight.com", "participant")), List.of()));
        List<RealmTenantMembership> members = client.membersOfRealm("fulfillment-demo-com");
        Assertions.assertEquals(2, members.size());
        Assertions.assertEquals("owner", members.get(0).getMembershipRole());
        Assertions.assertEquals("fastfreight.com", members.get(1).getOrganizationRefName());
    }

    @Test
    public void mapsUserRealmRolesIncludingStatus() {
        RemoteMembershipClient client = new RemoteMembershipClient(endpoint(List.of(), List.of(
            role("fulfillment-demo-com", List.of("admin"), "active"),
            role("other-com", List.of("viewer"), "suspended"))));
        List<UserRealmRole> assignments = client.realmsForUser("pat@acme.com");
        Assertions.assertEquals(2, assignments.size());
        Assertions.assertEquals(List.of("admin"), assignments.get(0).getRoles());
        Assertions.assertEquals("suspended", assignments.get(1).getStatus());
    }

    @Test
    public void unreachableControlPlaneFailsLoud() {
        RemoteMembershipClient client = new RemoteMembershipClient(new DefaultEndpoint() {
            @Override public RealmCatalogEntry findRealmByEmailDomain(String e) { return null; }
            @Override public RealmCatalogEntry findRealmByRefName(String r) { return null; }
            @Override public RealmCatalogEntry registerRealm(RealmCatalogEntry b) { return b; }
            @Override public List<RealmMembershipEntry> membersOfRealm(String refName) {
                throw new ProcessingException("connection refused");
            }
            @Override public List<UserRealmRoleEntry> realmsForUser(String userId) { return List.of(); }
        });
        Assertions.assertTrue(Assertions.assertThrows(IllegalStateException.class,
            () -> client.membersOfRealm("x")).getMessage().contains("no local fallback"));
    }
}
