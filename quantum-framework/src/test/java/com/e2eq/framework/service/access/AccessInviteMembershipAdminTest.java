package com.e2eq.framework.service.access;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.rest.requests.AccessInviteRequest;
import com.e2eq.framework.rest.responses.AccessInviteResponse;
import com.e2eq.framework.rest.responses.AccessMemberResponse;
import com.e2eq.framework.service.access.AccessInviteException.Code;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.EnvConfigUtils;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Membership administrators ({@code quantum.access-invites.membership-admin-roles}) manage the
 * invites and list the members of the tenant of their verified token only, and
 * {@code quantum.access-invites.create-roles} limits who may invite. Default behaviour (both unset)
 * is covered by {@link AccessInvitePooledRealmTest} and {@link AccessInviteServiceTest}.
 */
@QuarkusTest
@TestProfile(AccessInviteMembershipAdminTest.MembershipAdminProfile.class)
class AccessInviteMembershipAdminTest extends BaseRepoTest {

    public static class MembershipAdminProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quantum.access-invites.membership-admin-roles", MEMBERSHIP_ADMIN,
                "quantum.access-invites.create-roles", MEMBERSHIP_ADMIN
            );
        }
    }

    private static final String MEMBERSHIP_ADMIN = "membership-admin";
    private static final String PASSWORD = "Inv1te-Members-P@ssw0rd!";
    private static final String POOLED = "invite-members-pooled";
    private static final String TENANT_A = "tenant-a.members";
    private static final String TENANT_B = "tenant-b.members";

    @Inject
    AccessInviteService inviteService;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    SystemDirectory systemDirectory;

    @Inject
    RealmMembershipService realmMembershipService;

    @Inject
    com.e2eq.framework.util.SecurityUtils securityUtils;

    private String suffix;
    private final List<String> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        registerPooledRealm();
    }

    @AfterEach
    void tearDown() {
        String systemRealm = envConfigUtils.getSystemRealm();
        for (String userId : userIds) {
            morphiaDataStoreWrapper.getDataStore(systemRealm).find(CredentialUserIdPassword.class)
                .filter(Filters.eq("userId", userId)).delete(new dev.morphia.DeleteOptions().multi(true));
            morphiaDataStoreWrapper.getDataStore(systemRealm).find(UserRealmRole.class)
                .filter(Filters.eq("userId", userId)).delete(new dev.morphia.DeleteOptions().multi(true));
        }
        morphiaDataStoreWrapper.getDataStore(systemRealm).find(UserProfile.class)
            .filter(Filters.in("userId", userIds)).delete(new dev.morphia.DeleteOptions().multi(true));
        morphiaDataStoreWrapper.getDataStore(POOLED).find(UserProfile.class)
            .filter(Filters.in("userId", userIds)).delete(new dev.morphia.DeleteOptions().multi(true));
        morphiaDataStoreWrapper.getDataStore(POOLED).find(AccessInvite.class)
            .filter(Filters.in("invitedByUserId", userIds)).delete(new dev.morphia.DeleteOptions().multi(true));
        userIds.clear();
    }

    // ---- invite management ----------------------------------------------------------------

    @Test
    void membershipAdministratorManagesEveryInviteOfItsTenantAndNoOther() {
        String otherAdminA = member("other-admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN, "viewer"));
        String adminB = member("admin-b", List.of(TENANT_B), List.of(MEMBERSHIP_ADMIN, "viewer"));
        AccessInviteResponse inviteA = create(otherAdminA, TENANT_A, request(user("for-a"), "viewer"));
        AccessInviteResponse inviteB = create(adminB, TENANT_B, request(user("for-b"), "viewer"));

        String adminA = member("admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN, "viewer"));
        PrincipalContext asAdminA = signedIn(adminA, TENANT_A);

        List<String> listed = as(asAdminA, () -> inviteService.listInvites(POOLED)).stream().map(AccessInviteResponse::getRefName).toList();
        assertTrue(listed.contains(inviteA.getRefName()), "an invite of its tenant from another inviter is listed");
        assertFalse(listed.contains(inviteB.getRefName()), "another tenant's invite is not listed");
        assertEquals(inviteA.getRefName(), as(asAdminA, () -> inviteService.getInvite(POOLED, inviteA.getRefName())).getRefName());
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asAdminA, () -> inviteService.getInvite(POOLED, inviteB.getRefName())));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asAdminA, () -> inviteService.resendInvite(POOLED, inviteB.getRefName(), null)));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asAdminA, () -> inviteService.revokeInvite(POOLED, inviteB.getRefName())));
        assertEquals(AccessInvite.Status.REVOKED.name(),
            as(asAdminA, () -> inviteService.revokeInvite(POOLED, inviteA.getRefName())).getStatus());
    }

    @Test
    void membershipAdministratorOfTwoTenantsManagesOnlyTheTenantOfItsToken() {
        String adminB = member("admin-b2", List.of(TENANT_B), List.of(MEMBERSHIP_ADMIN, "viewer"));
        AccessInviteResponse inviteB = create(adminB, TENANT_B, request(user("for-b2"), "viewer"));

        String adminBoth = member("admin-both", List.of(TENANT_A, TENANT_B), List.of(MEMBERSHIP_ADMIN, "viewer"));
        PrincipalContext tokenForA = signedIn(adminBoth, TENANT_A);
        assertFalse(as(tokenForA, () -> inviteService.listInvites(POOLED)).stream()
            .anyMatch(invite -> invite.getRefName().equals(inviteB.getRefName())));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(tokenForA, () -> inviteService.getInvite(POOLED, inviteB.getRefName())));
        assertEquals(inviteB.getRefName(),
            as(signedIn(adminBoth, TENANT_B), () -> inviteService.getInvite(POOLED, inviteB.getRefName())).getRefName());
    }

    @Test
    void tokenTenantTheAssignmentDoesNotAuthorizeGrantsNothing() {
        String adminB = member("admin-b3", List.of(TENANT_B), List.of(MEMBERSHIP_ADMIN, "viewer"));
        AccessInviteResponse inviteB = create(adminB, TENANT_B, request(user("for-b3"), "viewer"));

        String adminA = member("admin-a3", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN, "viewer"));
        assertRefused(Code.INVITE_NOT_AUTHORIZED,
            () -> as(signedIn(adminA, TENANT_B), () -> inviteService.getInvite(POOLED, inviteB.getRefName())));
    }

    @Test
    void flatMembershipAdminRoleDoesNotCount() {
        String otherAdminA = member("other-admin-a4", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN, "viewer"));
        AccessInviteResponse inviteA = create(otherAdminA, TENANT_A, request(user("for-a4"), "viewer"));

        String flat = user("flat-admin");
        createAccount(flat, Set.of("user", MEMBERSHIP_ADMIN), home(TENANT_A));
        writeAssignment(flat, List.of("viewer"), List.of(TENANT_A), UserRealmRole.STATUS_ACTIVE);
        PrincipalContext asFlat = signedIn(flat, TENANT_A);

        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asFlat, () -> inviteService.getInvite(POOLED, inviteA.getRefName())));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> create(flat, TENANT_A, request(user("by-flat"), "viewer")));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asFlat, () -> inviteService.listTenantMembers(POOLED, TENANT_A)));
    }

    @Test
    void suspendedAssignmentGrantsNothing() {
        String otherAdminA = member("other-admin-a5", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN, "viewer"));
        AccessInviteResponse inviteA = create(otherAdminA, TENANT_A, request(user("for-a5"), "viewer"));

        String suspended = member("suspended-admin", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN, "viewer"));
        writeAssignment(suspended, List.of(MEMBERSHIP_ADMIN, "viewer"), List.of(TENANT_A), UserRealmRole.STATUS_SUSPENDED);
        PrincipalContext asSuspended = signedIn(suspended, TENANT_A);

        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asSuspended, () -> inviteService.getInvite(POOLED, inviteA.getRefName())));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> create(suspended, TENANT_A, request(user("by-suspended"), "viewer")));
    }

    // ---- create roles ---------------------------------------------------------------------

    @Test
    void createRolesRefuseAPlainMember() {
        String plain = member("plain-a", List.of(TENANT_A), List.of("viewer"));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> create(plain, TENANT_A, request(user("by-plain"), "viewer")));
    }

    @Test
    void realmAdministratorMayStillInviteWithoutACreateRole() {
        String admin = member("realm-admin", List.of(TENANT_A), List.of("admin", "viewer"));
        AccessInviteResponse invite = create(admin, TENANT_A, request(user("by-realm-admin"), "viewer"));
        assertEquals(AccessInvite.Status.PENDING.name(), invite.getStatus());
    }

    // ---- members ----------------------------------------------------------------------------

    @Test
    void membershipAdministratorListsTheMembersOfItsTenantOnly() {
        String adminA = member("list-admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN, "viewer"));
        String memberA = member("list-member-a", List.of(TENANT_A), List.of("viewer"));
        String memberB = member("list-member-b", List.of(TENANT_B), List.of("viewer"));
        PrincipalContext asAdminA = signedIn(adminA, TENANT_A);

        List<AccessMemberResponse> members = as(asAdminA, () -> inviteService.listTenantMembers(POOLED, null));
        List<String> memberIds = members.stream().map(AccessMemberResponse::getUserId).toList();
        assertTrue(memberIds.containsAll(List.of(adminA, memberA)), "the token tenant is the default: " + memberIds);
        assertFalse(memberIds.contains(memberB), "another tenant's member is not listed");
        assertTrue(members.stream().allMatch(member -> TENANT_A.equals(member.getTenantId())));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asAdminA, () -> inviteService.listTenantMembers(POOLED, TENANT_B)));
    }

    @Test
    void plainMemberCannotListMembers() {
        String plain = member("list-plain-a", List.of(TENANT_A), List.of("viewer"));
        assertRefused(Code.INVITE_NOT_AUTHORIZED,
            () -> as(signedIn(plain, TENANT_A), () -> inviteService.listTenantMembers(POOLED, TENANT_A)));
    }

    @Test
    void realmAdministratorListsMembersOfTenantsItsAssignmentAuthorizes() {
        String admin = member("list-realm-admin", List.of(TENANT_A), List.of("admin", "viewer"));
        String memberB = member("list-member-b2", List.of(TENANT_B), List.of("viewer"));
        PrincipalContext asAdmin = signedIn(admin, TENANT_A);
        assertTrue(as(asAdmin, () -> inviteService.listTenantMembers(POOLED, TENANT_A)).stream()
            .anyMatch(member -> admin.equals(member.getUserId())));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asAdmin, () -> inviteService.listTenantMembers(POOLED, TENANT_B)));
        assertFalse(memberB.isBlank());
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private void registerPooledRealm() {
        asSystem(() -> {
            Realm realm = systemDirectory.findRealmByRefName(POOLED).orElseGet(() -> systemDirectory.registerRealm(Realm.builder()
                .refName(POOLED)
                .displayName(POOLED)
                .databaseName(POOLED)
                .emailDomain(POOLED + ".invite-test.example")
                .tenancyMode(RealmTenancyMode.MULTI_TENANT)
                .domainContext(DomainContext.builder()
                    .tenantId(TENANT_A).orgRefName("org-a").accountId("acct-a").defaultRealm(POOLED).build())
                .build()));
            ensureMembership(TENANT_A, "org-a", "acct-a");
            ensureMembership(TENANT_B, "org-b", "acct-b");
            return realm;
        });
    }

    private void ensureMembership(String tenantId, String org, String account) {
        if (realmMembershipService.tenantInRealm(POOLED, tenantId).isPresent()) {
            return;
        }
        realmMembershipService.upsertMembership(RealmTenantMembership.builder()
            .refName(org + "-" + POOLED)
            .displayName(org + " in " + POOLED)
            .realmRefName(POOLED)
            .tenantId(tenantId)
            .organizationRefName(org)
            .accountId(account)
            .membershipRole(TENANT_A.equals(tenantId) ? RealmTenantMembership.MEMBERSHIP_ROLE_OWNER : RealmTenantMembership.MEMBERSHIP_ROLE_PARTICIPANT)
            .participationStatus(RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE)
            .build());
    }

    private String user(String label) {
        String userId = label + "-" + suffix + "@members-invite-test.example";
        userIds.add(userId);
        return userId;
    }

    /** A user whose home tenant is the first of {@code tenants}, with a realm assignment holding {@code roles}. */
    private String member(String label, List<String> tenants, List<String> roles) {
        String userId = user(label);
        createAccount(userId, Set.of("user"), home(tenants.get(0)));
        writeAssignment(userId, roles, tenants, UserRealmRole.STATUS_ACTIVE);
        return userId;
    }

    private static DataDomain partition(String tenant) {
        boolean a = TENANT_A.equals(tenant);
        return DataDomain.builder()
            .tenantId(tenant)
            .orgRefName(a ? "org-a" : "org-b")
            .accountNum(a ? "acct-a" : "acct-b")
            .dataSegment(0)
            .ownerId("unset")
            .build();
    }

    private static DomainContext home(String tenant) {
        boolean a = TENANT_A.equals(tenant);
        return DomainContext.builder().tenantId(tenant).defaultRealm(POOLED)
            .orgRefName(a ? "org-a" : "org-b").accountId(a ? "acct-a" : "acct-b").build();
    }

    /** The verified principal of {@code userId} signed in to POOLED with a token for {@code tenant}. */
    private PrincipalContext signedIn(String userId, String tenant) {
        // Admin principal roles only permit the policy-governed save; invite authority comes from
        // the caller's credential and realm assignment.
        return new PrincipalContext.Builder()
            .withDefaultRealm(POOLED)
            .withDataDomain(PooledRealmTenantInvitePolicy.copy(partition(tenant), userId))
            .withUserId(userId)
            .withScope("Authentication")
            .withRoles(new String[]{"admin", "user"})
            .build();
    }

    private AccessInviteRequest request(String email, String... roles) {
        AccessInviteRequest request = new AccessInviteRequest();
        request.setEmail(email);
        request.setGrantedRoles(new ArrayList<>(List.of(roles)));
        return request;
    }

    private AccessInviteResponse create(String inviter, String tenant, AccessInviteRequest request) {
        return as(signedIn(inviter, tenant), () -> inviteService.createInvite(POOLED, inviter, request));
    }

    private void createAccount(String userId, Set<String> roles, DomainContext domainContext) {
        asSystem(() -> authProviderFactory.getUserManager().createUser(userId, PASSWORD, false, roles, domainContext));
    }

    private void writeAssignment(String userId, List<String> roles, List<String> tenants, String status) {
        asSystem(() -> {
            UserRealmRole assignment = realmMembershipService.realmsForUser(userId).stream()
                .filter(candidate -> POOLED.equals(candidate.getRealmRefName()))
                .findFirst()
                .orElseGet(() -> UserRealmRole.builder()
                    .refName("user-realm-role:" + userId + ":" + POOLED)
                    .userId(userId)
                    .realmRefName(POOLED)
                    .dataDomain(PooledRealmTenantInvitePolicy.copy(partition(tenants.get(0)), userId))
                    .build());
            assignment.setRoles(new ArrayList<>(roles));
            assignment.setAuthorizedTenantIds(new ArrayList<>(tenants));
            assignment.setStatus(status);
            return realmMembershipService.upsertUserRealmRole(assignment);
        });
    }

    private <T> T as(PrincipalContext principal, Supplier<T> action) {
        return SecurityCallScope.runWithContexts(principal, rContext, action);
    }

    /** Test fixtures are written as the framework system principal, like the service's own writes. */
    private <T> T asSystem(Supplier<T> action) {
        return SecurityCallScope.runWithContexts(securityUtils.getSystemPrincipalContext(), securityUtils.getSystemSecurityResourceContext(), action);
    }

    private static void assertRefused(Code expected, Executable action) {
        AccessInviteException refused = assertThrows(AccessInviteException.class, action);
        assertEquals(expected, refused.getCode(), refused.getMessage());
    }
}
