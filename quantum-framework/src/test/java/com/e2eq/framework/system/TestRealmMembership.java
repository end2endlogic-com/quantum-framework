package com.e2eq.framework.system;

import com.e2eq.framework.model.persistent.morphia.RealmTenantMembershipRepo;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.system.membership.RealmMembershipService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * B4 membership model exit test (realm-membership ADR): orgs/accounts are
 * realm members with exactly one owner; users hold per-realm role
 * assignments resolved through RealmMembershipService.
 */
@QuarkusTest
public class TestRealmMembership extends BaseRepoTest {

    @Inject RealmMembershipService membershipService;
    @Inject RealmTenantMembershipRepo membershipRepo;
    @Inject UserRealmRoleRepo userRealmRoleRepo;

    private static final String REALM = "membership-test-realm-com";

    @Test
    public void ownerAndParticipantsResolveAndUserRolesArePerRealm() {
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            seed();

            var members = membershipService.membersOfRealm(REALM);
            Assertions.assertEquals(3, members.size(), "owner + two participants");

            var owner = membershipService.ownerOfRealm(REALM);
            Assertions.assertTrue(owner.isPresent());
            Assertions.assertEquals("acme.com", owner.get().getOrganizationRefName());

            var carrierRealms = membershipService.membershipsForOrg("fastfreight.com");
            Assertions.assertEquals(1, carrierRealms.size());
            Assertions.assertEquals(RealmTenantMembership.MEMBERSHIP_ROLE_PARTICIPANT,
                carrierRealms.get(0).getMembershipRole());

            Assertions.assertEquals(List.of("admin"),
                membershipService.rolesForUser("pat@acme.com", REALM));
            Assertions.assertEquals(List.of("viewer"),
                membershipService.rolesForUser("pat@acme.com", "other-realm-com"),
                "same identity, different role in a different realm");
            Assertions.assertTrue(
                membershipService.rolesForUser("pat@acme.com", "no-such-realm").isEmpty(),
                "non-member resolves to no roles");
            Assertions.assertTrue(
                membershipService.rolesForUser("suspended@acme.com", REALM).isEmpty(),
                "suspended assignments resolve to no roles");
        } finally {
            cleanup();
        }
    }

    private void seed() {
        membershipRepo.save(membership("acme.com", RealmTenantMembership.MEMBERSHIP_ROLE_OWNER));
        membershipRepo.save(membership("fastfreight.com", RealmTenantMembership.MEMBERSHIP_ROLE_PARTICIPANT));
        membershipRepo.save(membership("partsco.com", RealmTenantMembership.MEMBERSHIP_ROLE_PARTICIPANT));

        userRealmRoleRepo.save(role("pat@acme.com", REALM, List.of("admin"), UserRealmRole.STATUS_ACTIVE));
        userRealmRoleRepo.save(role("pat@acme.com", "other-realm-com", List.of("viewer"), UserRealmRole.STATUS_ACTIVE));
        userRealmRoleRepo.save(role("suspended@acme.com", REALM, List.of("admin"), UserRealmRole.STATUS_SUSPENDED));
    }

    private RealmTenantMembership membership(String org, String role) {
        RealmTenantMembership m = RealmTenantMembership.builder()
            .refName("membership-" + org + "-" + REALM)
            .displayName(org + " in " + REALM)
            .realmRefName(REALM)
            .organizationRefName(org)
            .membershipRole(role)
            .build();
        m.setDataDomain(testUtils.getTestDataDomain());
        return m;
    }

    private UserRealmRole role(String userId, String realm, List<String> roles, String status) {
        UserRealmRole r = UserRealmRole.builder()
            .refName("urr-" + userId + "-" + realm)
            .displayName(userId + "@" + realm)
            .userId(userId)
            .realmRefName(realm)
            .roles(roles)
            .status(status)
            .build();
        r.setDataDomain(testUtils.getTestDataDomain());
        return r;
    }

    private void cleanup() {
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            membershipRepo.getListByQuery(0, -1, "realmRefName:" + REALM)
                .forEach(this::deleteQuietly);
            userRealmRoleRepo.getListByQuery(0, -1, "userId:pat@acme.com")
                .forEach(this::deleteRoleQuietly);
            userRealmRoleRepo.getListByQuery(0, -1, "userId:suspended@acme.com")
                .forEach(this::deleteRoleQuietly);
        }
    }

    private void deleteQuietly(RealmTenantMembership m) {
        try {
            membershipRepo.delete(m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteRoleQuietly(UserRealmRole r) {
        try {
            userRealmRoleRepo.delete(r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
