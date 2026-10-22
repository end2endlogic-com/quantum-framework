package com.e2eq.framework.service.access;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.RoleSource;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.rest.filters.TenantSelectionProbe;
import com.e2eq.framework.rest.requests.AccessInviteAcceptRequest;
import com.e2eq.framework.rest.requests.AccessInviteRequest;
import com.e2eq.framework.rest.requests.AccessInviteScopeClaim;
import com.e2eq.framework.rest.responses.AccessInviteAcceptResponse;
import com.e2eq.framework.rest.responses.AccessInviteResponse;
import com.e2eq.framework.service.access.AccessInviteException.Code;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.EnvConfigUtils;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invites in a pooled realm (several tenants in one realm, separated by DataDomain): the invite
 * carries the inviter's resolved partition and the {@code pooled-realm-tenant} acceptance policy
 * places the invitee in it.
 */
@QuarkusTest
class AccessInvitePooledRealmTest extends BaseRepoTest {

    private static final String PASSWORD = "Inv1te-Pooled-P@ssw0rd!";

    /** Pooled (MULTI_TENANT) realm: its tenancy mode selects the pooled policy. */
    private static final String POOLED = "invite-pooled-test";
    /** A second pooled realm, for cross-realm refusals. */
    private static final String POOLED_OTHER = "invite-pooled-other";

    /** SHARED deployment but SINGLE_TENANT: request tenant selection does not run there. */
    private static final String SHARED_SINGLE = "invite-shared-single";

    private static final String TENANT_A = "tenant-a.pooled";
    private static final String TENANT_B = "tenant-b.pooled";

    @Inject
    AccessInviteService inviteService;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    IdentityRoleResolver identityRoleResolver;

    @Inject
    SystemDirectory systemDirectory;

    @Inject
    RealmMembershipService realmMembershipService;

    @Inject
    com.e2eq.framework.util.SecurityUtils securityUtils;

    private String suffix;
    private final List<String> userIds = new ArrayList<>();
    private static final List<String> REALMS = List.of(POOLED, POOLED_OTHER, SHARED_SINGLE);

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        registerPooledRealm(POOLED);
        registerPooledRealm(POOLED_OTHER);
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
        for (String r : REALMS) {
            morphiaDataStoreWrapper.getDataStore(r).find(UserProfile.class)
                .filter(Filters.in("userId", userIds)).delete(new dev.morphia.DeleteOptions().multi(true));
            morphiaDataStoreWrapper.getDataStore(r).find(AccessInvite.class)
                .filter(Filters.in("invitedByUserId", userIds)).delete(new dev.morphia.DeleteOptions().multi(true));
        }
        userIds.clear();
    }

    // ---- placement ------------------------------------------------------------------------

    @Test
    void inviteeLandsInTheInvitersTenantOnly() {
        String inviter = member("inviter-a", TENANT_A, List.of("viewer"));
        String invitee = user("invitee");

        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, inviter, partition(TENANT_A, 0)), request(invitee, "viewer"));
        assertEquals(PooledRealmTenantInvitePolicy.ID, invite.getAcceptancePolicy());
        assertEquals(TENANT_A, invite.getScope().getDataDomain().getTenantId());
        assertEquals(POOLED, invite.getScope().getRealmRefName());
        AccessInvite stored = storedInvite(POOLED, invite);
        assertEquals(TENANT_A, stored.getDataDomain().getTenantId(), "the invite record lives in the inviter's partition");
        assertEquals(inviter, stored.getDataDomain().getOwnerId());

        AccessInviteAcceptResponse accepted = acceptAnonymously(POOLED, invite, invitee);
        assertEquals(TENANT_A, accepted.getTenantId());

        UserRealmRole assignment = assignment(invitee, POOLED);
        assertEquals(List.of(TENANT_A), assignment.getAuthorizedTenantIds());
        assertEquals(TENANT_A, assignment.getDataDomain().getTenantId());
        assertEquals("org-a", assignment.getDataDomain().getOrgRefName());
        assertEquals("acct-a", assignment.getDataDomain().getAccountNum());
        assertEquals(invitee, assignment.getDataDomain().getOwnerId(), "the invitee owns its partition, not the inviter");
        assertEquals("org-a", assignment.getSponsoringOrgRefName());

        CredentialUserIdPassword credential = credential(invitee);
        assertEquals(TENANT_A, credential.getDomainContext().getTenantId());
        assertEquals(POOLED, credential.getDomainContext().getDefaultRealm());
        assertEquals("org-a", credential.getDomainContext().getOrgRefName());
        assertEquals("acct-a", credential.getDomainContext().getAccountId());
        assertEquals(List.of("user"), List.of(credential.getRoles()), "invited roles never land on flat credential roles");

        UserProfile profile = profile(POOLED, invitee);
        assertEquals(TENANT_A, profile.getDataDomain().getTenantId());
        assertEquals(invitee, profile.getDataDomain().getOwnerId());

        assertEquals(Set.of("viewer"), realmRoles(invitee, POOLED));

        // The request path: the invitee enters tenant A with A's partition and is refused tenant B.
        PrincipalContext inA = select(invitee, null, TENANT_A);
        assertEquals(TENANT_A, inA.getDataDomain().getTenantId());
        assertEquals("org-a", inA.getDataDomain().getOrgRefName());
        assertEquals(invitee, inA.getDataDomain().getOwnerId());
        assertThrows(ForbiddenException.class, () -> select(invitee, TENANT_B, TENANT_A));
        // The invitee can sign in to the pooled realm; the token names its tenant.
        com.e2eq.framework.model.auth.AuthProvider.LoginResponse login = asSystem(() -> authProviderFactory.getAuthProvider()
            .login(invitee, PASSWORD, "quantum-framework-test", POOLED));
        assertTrue(login.authenticated(), () -> "login refused: " + login.negativeResponse());
        assertEquals(POOLED, login.positiveResponse().realm());
        assertTrue(login.positiveResponse().roles().contains("viewer"));
    }

    @Test
    void partitionWithADataSegmentIsCarriedToTheInvitee() {
        String inviter = member("inviter-seg", TENANT_A, List.of("viewer"));
        String invitee = user("invitee-seg");

        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, inviter, partition(TENANT_A, 4)), request(invitee, "viewer"));
        assertEquals(4, invite.getScope().getDataDomain().getDataSegment());
        acceptAnonymously(POOLED, invite, invitee);

        assertEquals(4, assignment(invitee, POOLED).getDataDomain().getDataSegment());
        assertEquals(4, credential(invitee).getDomainContext().getDataSegment());
        assertEquals(4, profile(POOLED, invitee).getDataDomain().getDataSegment());
        PrincipalContext inA = select(invitee, null, TENANT_A);
        assertEquals(4, inA.getDataDomain().getDataSegment(), "the request partition keeps the data segment");
    }

    @Test
    void partitionRuleOnAFieldOtherThanTenantIsCarried() {
        String inviter = member("inviter-loc", TENANT_A, List.of("viewer"));
        String invitee = user("invitee-loc");
        // A DataDomainPolicy rule (not the request) places invites at a location within tenant A.
        DataDomain fixed = partition(TENANT_A, 2);
        fixed.setOwnerId(inviter);
        fixed.setLocationId("dock-7");
        PrincipalContext principal = principalBuilder(POOLED, inviter, partition(TENANT_A, 0))
            .withDataDomainPolicy(policy("SECURITY:ACCESS_INVITE", DataDomainPolicyEntry.ResolutionMode.FIXED, fixed, null))
            .build();

        AccessInviteResponse invite = create(POOLED, principal, request(invitee, "viewer"));
        assertEquals("dock-7", invite.getScope().getDataDomain().getLocationId());
        assertEquals(2, invite.getScope().getDataDomain().getDataSegment());
        acceptAnonymously(POOLED, invite, invitee);

        UserRealmRole assignment = assignment(invitee, POOLED);
        assertEquals("dock-7", assignment.getDataDomain().getLocationId());
        assertEquals(2, assignment.getDataDomain().getDataSegment());
        assertEquals("dock-7", profile(POOLED, invitee).getDataDomain().getLocationId());
        PrincipalContext inA = select(invitee, null, TENANT_A);
        assertEquals("dock-7", inA.getDataDomain().getLocationId());
        assertEquals(2, inA.getDataDomain().getDataSegment());
    }

    @Test
    void partitionWithAPolicyFilterIsRefused() {
        String inviter = member("inviter-filter", TENANT_A, List.of("viewer"));
        PrincipalContext principal = principalBuilder(POOLED, inviter, partition(TENANT_A, 0))
            .withDataDomainPolicy(policy("SECURITY:*", DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL, null, "dataSegment <= 2"))
            .build();
        assertRefused(Code.INVITE_SCOPE_UNRESOLVABLE, () -> create(POOLED, principal, request(user("x"), "viewer")));
    }

    @Test
    void partitionWithAnOrgOutsideTheTenantMembershipIsRefused() {
        String inviter = member("inviter-org", TENANT_A, List.of("viewer"));
        DataDomain otherOrg = partition(TENANT_A, 0);
        otherOrg.setOrgRefName("org-elsewhere");
        assertRefused(Code.INVITE_SCOPE_UNRESOLVABLE,
            () -> create(POOLED, inviterIn(POOLED, inviter, otherOrg), request(user("x"), "viewer")));
    }

    @Test
    void partitionResolvedInAnotherRealmIsRefused() {
        String inviter = member("inviter-elsewhere", TENANT_A, List.of("viewer"));
        // The inviter's verified context is another realm; its partition there says nothing about this one.
        assertRefused(Code.INVITE_SCOPE_UNRESOLVABLE,
            () -> create(POOLED, inviterIn(POOLED_OTHER, inviter, partition(TENANT_A, 0)), request(user("x"), "viewer")));
    }

    // ---- entitlement ----------------------------------------------------------------------

    @Test
    void inviterNotAuthorizedForTheTenantCannotInviteIntoIt() {
        String inviter = member("inviter-only-a", TENANT_A, List.of("viewer"));
        assertRefused(Code.INVITE_SCOPE_NOT_AUTHORIZED,
            () -> create(POOLED, inviterIn(POOLED, inviter, partition(TENANT_B, 0)), request(user("x"), "viewer")));
    }

    @Test
    void realmAdministratorMayInviteIntoAnyTenant() {
        String admin = user("realm-admin");
        createAccount(admin, Set.of("admin", "user"), homeIn(TENANT_A));
        String invitee = user("invitee-admin");
        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, admin, partition(TENANT_B, 0)), request(invitee));
        acceptAnonymously(POOLED, invite, invitee);
        assertEquals(List.of(TENANT_B), assignment(invitee, POOLED).getAuthorizedTenantIds());
    }

    @Test
    void scopeIsTakenFromTheTokenNotTheBody() {
        String inviter = member("inviter-body", TENANT_A, List.of("viewer"));
        PrincipalContext principal = inviterIn(POOLED, inviter, partition(TENANT_A, 0));

        AccessInviteRequest claimsB = request(user("x"), "viewer");
        claimsB.setScope(AccessInviteScopeClaim.builder().tenantId(TENANT_B).build());
        assertRefused(Code.INVITE_SCOPE_NOT_AUTHORIZED, () -> create(POOLED, principal, claimsB));

        AccessInviteRequest claimsSegment = request(user("y"), "viewer");
        claimsSegment.setScope(AccessInviteScopeClaim.builder().tenantId(TENANT_A).dataSegment(9).build());
        assertRefused(Code.INVITE_SCOPE_NOT_AUTHORIZED, () -> create(POOLED, principal, claimsSegment));

        // Echoing the verified scope is accepted.
        AccessInviteRequest echoes = request(user("z"), "viewer");
        echoes.setScope(AccessInviteScopeClaim.builder().realm(POOLED).tenantId(TENANT_A).orgRefName("org-a").dataSegment(0).build());
        assertEquals(TENANT_A, create(POOLED, principal, echoes).getScope().getDataDomain().getTenantId());
    }

    @Test
    void inviterWhoLosesTheTenantBeforeAcceptanceLeavesTheInvitePending() {
        String inviter = member("inviter-loses", TENANT_A, List.of("viewer"));
        String invitee = user("invitee-pending");
        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, inviter, partition(TENANT_A, 0)), request(invitee, "viewer"));

        writeAssignment(inviter, List.of("viewer"), List.of(TENANT_B), partition(TENANT_B, 0));

        assertRefused(Code.INVITE_SCOPE_NOT_AUTHORIZED, () -> acceptAnonymously(POOLED, invite, invitee));
        assertEquals(AccessInvite.Status.PENDING, storedInvite(POOLED, invite).getStatus());
        assertTrue(findCredential(invitee).isEmpty(), "no account is left behind");
        assertTrue(assignments(invitee).isEmpty(), "no assignment is left behind");
    }

    // ---- several tenants ------------------------------------------------------------------

    @Test
    void existingMemberOfAJoinsBAndKeepsBothWithMergedRoles() {
        // The inviter works in tenant B and is also entitled to A, so the realm-scoped role it adds
        // reaches no tenant it could not grant itself.
        String inviterB = member("inviter-b", TENANT_B, List.of("editor"));
        writeAssignment(inviterB, List.of("editor"), List.of(TENANT_B, TENANT_A), partition(TENANT_B, 0));
        String existing = user("member-a");
        createAccount(existing, Set.of("user"), homeIn(TENANT_A));
        writeAssignment(existing, List.of("viewer"), List.of(TENANT_A), partition(TENANT_A, 0));

        AccessInviteRequest request = new AccessInviteRequest();
        request.setTargetUserId(existing);
        request.setGrantedRoles(List.of("editor"));
        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, inviterB, partition(TENANT_B, 0)), request);

        AccessInviteAcceptRequest accept = accept(POOLED, invite.getInviteToken());
        AccessInviteAcceptResponse accepted = as(principalBuilder(POOLED, existing, partition(TENANT_A, 0)).withRoles(new String[]{"user"}).build(),
            () -> inviteService.acceptInvite(accept));
        assertEquals(TENANT_B, accepted.getTenantId());

        UserRealmRole assignment = assignment(existing, POOLED);
        assertEquals(List.of(TENANT_A, TENANT_B), assignment.getAuthorizedTenantIds());
        assertEquals(TENANT_A, assignment.getDataDomain().getTenantId(), "the member keeps its default partition");
        assertEquals(Set.of("viewer", "editor"), realmRoles(existing, POOLED));
        assertEquals(TENANT_A, credential(existing).getDomainContext().getTenantId(), "an existing home context is untouched");

        assertEquals(TENANT_A, select(existing, null, TENANT_A).getDataDomain().getTenantId());
        PrincipalContext inB = select(existing, TENANT_B, TENANT_A);
        assertEquals(TENANT_B, inB.getDataDomain().getTenantId());
        assertEquals("org-b", inB.getDataDomain().getOrgRefName());
    }

    @Test
    void addingARoleThatWouldReachATenantTheInviterLacksIsRefused() {
        String inviterB = member("inviter-b-only", TENANT_B, List.of("editor", "viewer"));
        String existing = user("member-a-roles");
        createAccount(existing, Set.of("user"), homeIn(TENANT_A));
        writeAssignment(existing, List.of("viewer"), List.of(TENANT_A), partition(TENANT_A, 0));
        PrincipalContext inB = inviterIn(POOLED, inviterB, partition(TENANT_B, 0));
        PrincipalContext member = principalBuilder(POOLED, existing, partition(TENANT_A, 0)).withRoles(new String[]{"user"}).build();

        AccessInviteRequest addsEditor = new AccessInviteRequest();
        addsEditor.setTargetUserId(existing);
        addsEditor.setGrantedRoles(List.of("editor"));
        AccessInviteResponse refused = create(POOLED, inB, addsEditor);
        assertRefused(Code.INVITE_ROLE_NOT_GRANTABLE, () -> as(member, () -> inviteService.acceptInvite(accept(POOLED, refused.getInviteToken()))));
        assertEquals(List.of(TENANT_A), assignment(existing, POOLED).getAuthorizedTenantIds());
        assertEquals(AccessInvite.Status.PENDING, storedInvite(POOLED, refused).getStatus());
        as(inB, () -> inviteService.revokeInvite(POOLED, refused.getRefName()));

        // Joining B with only roles the member already holds is fine.
        AccessInviteRequest sameRoles = new AccessInviteRequest();
        sameRoles.setTargetUserId(existing);
        sameRoles.setGrantedRoles(List.of("viewer"));
        AccessInviteResponse ok = create(POOLED, inB, sameRoles);
        as(member, () -> inviteService.acceptInvite(accept(POOLED, ok.getInviteToken())));
        assertEquals(List.of(TENANT_A, TENANT_B), assignment(existing, POOLED).getAuthorizedTenantIds());
        assertEquals(Set.of("viewer"), realmRoles(existing, POOLED));
    }

    @Test
    void addingAnApplicationThatWouldReachATenantTheInviterLacksIsRefused() {
        // Applications live once on the realm-wide assignment, like roles: a B-only inviter must
        // not hand the member's existing A membership an application.
        String inviterB = member("inviter-b-apps", TENANT_B, List.of("viewer"));
        writeApplications(inviterB, POOLED, List.of("app-x"));
        String existing = user("member-a-apps");
        createAccount(existing, Set.of("user"), homeIn(TENANT_A));
        writeAssignment(existing, List.of("viewer"), List.of(TENANT_A), partition(TENANT_A, 0));
        PrincipalContext inB = inviterIn(POOLED, inviterB, partition(TENANT_B, 0));
        PrincipalContext member = principalBuilder(POOLED, existing, partition(TENANT_A, 0)).withRoles(new String[]{"user"}).build();

        AccessInviteRequest addsApp = new AccessInviteRequest();
        addsApp.setTargetUserId(existing);
        addsApp.setGrantedRoles(List.of("viewer"));
        addsApp.setAuthorizedApplications(List.of("app-x"));
        AccessInviteResponse refused = create(POOLED, inB, addsApp);
        assertRefused(Code.INVITE_APPLICATION_NOT_GRANTABLE, () -> as(member, () -> inviteService.acceptInvite(accept(POOLED, refused.getInviteToken()))));
        assertEquals(List.of(TENANT_A), assignment(existing, POOLED).getAuthorizedTenantIds());
        assertNoApplications(assignment(existing, POOLED));
        assertEquals(AccessInvite.Status.PENDING, storedInvite(POOLED, refused).getStatus());
        as(inB, () -> inviteService.revokeInvite(POOLED, refused.getRefName()));

        // The same inviter without an application in the invite is unaffected.
        AccessInviteRequest noApp = new AccessInviteRequest();
        noApp.setTargetUserId(existing);
        noApp.setGrantedRoles(List.of("viewer"));
        AccessInviteResponse ok = create(POOLED, inB, noApp);
        as(member, () -> inviteService.acceptInvite(accept(POOLED, ok.getInviteToken())));
        assertEquals(List.of(TENANT_A, TENANT_B), assignment(existing, POOLED).getAuthorizedTenantIds());
        assertNoApplications(assignment(existing, POOLED));
    }

    @Test
    void inviterEntitledToEveryTenantOfTheMemberMayAddAnApplication() {
        String inviterAB = member("inviter-ab-apps", TENANT_B, List.of("viewer"));
        writeAssignment(inviterAB, List.of("viewer"), List.of(TENANT_B, TENANT_A), partition(TENANT_B, 0));
        writeApplications(inviterAB, POOLED, List.of("app-x"));
        String existing = user("member-a-apps-ok");
        createAccount(existing, Set.of("user"), homeIn(TENANT_A));
        writeAssignment(existing, List.of("viewer"), List.of(TENANT_A), partition(TENANT_A, 0));

        AccessInviteRequest addsApp = new AccessInviteRequest();
        addsApp.setTargetUserId(existing);
        addsApp.setGrantedRoles(List.of("viewer"));
        addsApp.setAuthorizedApplications(List.of("app-x"));
        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, inviterAB, partition(TENANT_B, 0)), addsApp);
        PrincipalContext member = principalBuilder(POOLED, existing, partition(TENANT_A, 0)).withRoles(new String[]{"user"}).build();
        as(member, () -> inviteService.acceptInvite(accept(POOLED, invite.getInviteToken())));
        assertEquals(List.of(TENANT_A, TENANT_B), assignment(existing, POOLED).getAuthorizedTenantIds());
        assertEquals(List.of("app-x"), assignment(existing, POOLED).getAuthorizedApplications());
    }

    @Test
    void realmAdministratorMayAddAnApplicationToAnExistingMember() {
        String admin = user("realm-admin-apps");
        createAccount(admin, Set.of("admin", "user"), homeIn(TENANT_B));
        String existing = user("member-a-apps-admin");
        createAccount(existing, Set.of("user"), homeIn(TENANT_A));
        writeAssignment(existing, List.of("viewer"), List.of(TENANT_A), partition(TENANT_A, 0));

        AccessInviteRequest addsApp = new AccessInviteRequest();
        addsApp.setTargetUserId(existing);
        addsApp.setAuthorizedApplications(List.of("app-x"));
        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, admin, partition(TENANT_B, 0)), addsApp);
        PrincipalContext member = principalBuilder(POOLED, existing, partition(TENANT_A, 0)).withRoles(new String[]{"user"}).build();
        as(member, () -> inviteService.acceptInvite(accept(POOLED, invite.getInviteToken())));
        assertEquals(List.of(TENANT_A, TENANT_B), assignment(existing, POOLED).getAuthorizedTenantIds());
        assertEquals(List.of("app-x"), assignment(existing, POOLED).getAuthorizedApplications());
    }

    @Test
    void existingMemberWhoseDefaultPartitionDiffersCannotTakeANarrowerPartition() {
        String inviter = member("inviter-seg-b", TENANT_B, List.of("viewer"));
        writeAssignment(inviter, List.of("viewer"), List.of(TENANT_B, TENANT_A), partition(TENANT_B, 0));
        String existing = user("member-a-seg");
        createAccount(existing, Set.of("user"), homeIn(TENANT_A));
        writeAssignment(existing, List.of("viewer"), List.of(TENANT_A), partition(TENANT_A, 0));

        AccessInviteRequest request = new AccessInviteRequest();
        request.setTargetUserId(existing);
        request.setGrantedRoles(List.of("viewer"));
        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, inviter, partition(TENANT_B, 3)), request);
        PrincipalContext member = principalBuilder(POOLED, existing, partition(TENANT_A, 0)).withRoles(new String[]{"user"}).build();
        // In tenant B the member would work in segment 0, wider than the inviter's segment 3.
        assertRefused(Code.INVITE_SCOPE_UNRESOLVABLE, () -> as(member, () -> inviteService.acceptInvite(accept(POOLED, invite.getInviteToken()))));
        assertEquals(List.of(TENANT_A), assignment(existing, POOLED).getAuthorizedTenantIds());
    }

    @Test
    void failedAcceptanceByAnExistingMemberRemovesOnlyWhatItAdded() {
        String inviterB = member("inviter-rb", TENANT_B, List.of("editor"));
        writeAssignment(inviterB, List.of("editor"), List.of(TENANT_B, TENANT_A), partition(TENANT_B, 0));
        String existing = user("member-a-rb");
        createAccount(existing, Set.of("user"), homeIn(TENANT_A));
        writeAssignment(existing, List.of("viewer"), List.of(TENANT_A), partition(TENANT_A, 0));

        AccessInviteRequest request = new AccessInviteRequest();
        request.setTargetUserId(existing);
        request.setGrantedRoles(List.of("editor"));
        request.setInviteMessage(FailingTestInviteProvisioner.FAIL_MARKER);
        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, inviterB, partition(TENANT_B, 0)), request);
        PrincipalContext member = principalBuilder(POOLED, existing, partition(TENANT_A, 0)).withRoles(new String[]{"user"}).build();

        assertRefused(Code.INVITE_PROVISIONING_FAILED, () -> as(member, () -> inviteService.acceptInvite(accept(POOLED, invite.getInviteToken()))));
        UserRealmRole after = assignment(existing, POOLED);
        assertEquals(List.of(TENANT_A), after.getAuthorizedTenantIds(), "the added tenant is removed");
        assertEquals(List.of("viewer"), after.getRoles(), "the added role is removed");
        assertEquals(UserRealmRole.STATUS_ACTIVE, after.getStatus());
        assertEquals(AccessInvite.Status.PENDING, storedInvite(POOLED, invite).getStatus());
    }

    @Test
    void failedAcceptanceOfANewInviteeRemovesItsProfile() {
        String inviter = member("inviter-rbp", TENANT_A, List.of("viewer"));
        String invitee = user("invitee-rbp");
        AccessInviteRequest request = request(invitee, "viewer");
        request.setInviteMessage(FailingTestInviteProvisioner.FAIL_MARKER);
        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, inviter, partition(TENANT_A, 0)), request);
        assertRefused(Code.INVITE_PROVISIONING_FAILED, () -> acceptAnonymously(POOLED, invite, invitee));
        assertTrue(findCredential(invitee).isEmpty());
        assertTrue(assignments(invitee).isEmpty());
        try (var cursor = morphiaDataStoreWrapper.getDataStore(POOLED).find(UserProfile.class)
            .filter(Filters.eq("userId", invitee)).iterator()) {
            assertTrue(cursor.toList().isEmpty(), "the profile created by the attempt is removed");
        }
    }

    // ---- invite management ----------------------------------------------------------------

    @Test
    void tenantAdministratorManagesOnlyItsTenantsInvites() {
        String inviterB = member("inviter-mgmt-b", TENANT_B, List.of("viewer"));
        String inviterA = member("inviter-mgmt-a", TENANT_A, List.of("viewer"));
        AccessInviteResponse inviteB = create(POOLED, inviterIn(POOLED, inviterB, partition(TENANT_B, 0)), request(user("for-b"), "viewer"));
        AccessInviteResponse inviteA = create(POOLED, inviterIn(POOLED, inviterA, partition(TENANT_A, 0)), request(user("for-a"), "viewer"));

        // Admin by its realm-scoped role, entitled to tenant A only.
        String adminA = member("admin-a", TENANT_A, List.of("admin", "viewer"));
        PrincipalContext asAdminA = inviterIn(POOLED, adminA, partition(TENANT_A, 0));

        List<String> listed = as(asAdminA, () -> inviteService.listInvites(POOLED)).stream().map(AccessInviteResponse::getRefName).toList();
        assertTrue(listed.contains(inviteA.getRefName()));
        assertTrue(!listed.contains(inviteB.getRefName()), "another tenant's invites are not listed");
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asAdminA, () -> inviteService.getInvite(POOLED, inviteB.getRefName())));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asAdminA, () -> inviteService.resendInvite(POOLED, inviteB.getRefName(), null)));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(asAdminA, () -> inviteService.revokeInvite(POOLED, inviteB.getRefName())));
        assertEquals(inviteA.getRefName(), as(asAdminA, () -> inviteService.getInvite(POOLED, inviteA.getRefName())).getRefName());
    }

    // ---- fail closed ----------------------------------------------------------------------

    @Test
    void sharedRealmThatIsNotMultiTenantIsNotServedByThePooledPolicy() {
        Realm shared = Realm.builder()
            .refName(SHARED_SINGLE).displayName(SHARED_SINGLE).databaseName(SHARED_SINGLE)
            .emailDomain(SHARED_SINGLE + ".invite-test.example")
            .tenancyMode(RealmTenancyMode.SINGLE_TENANT)
            .deploymentType(com.e2eq.framework.model.security.RealmDeploymentType.SHARED)
            .domainContext(DomainContext.builder().tenantId(TENANT_A).orgRefName("org-a").accountId("acct-a").defaultRealm(SHARED_SINGLE).build())
            .build();
        Realm registered = asSystem(() -> systemDirectory.findRealmByRefName(SHARED_SINGLE).orElseGet(() -> systemDirectory.registerRealm(shared)));
        String inviter = member("inviter-shared", TENANT_A, List.of("viewer"), SHARED_SINGLE);
        assertRefused(Code.INVITE_POLICY_UNAVAILABLE,
            () -> create(registered.getRefName(), inviterIn(SHARED_SINGLE, inviter, partition(TENANT_A, 0)), request(user("x"))));
    }


    @Test
    void pooledRealmUsesThePooledPolicyFromItsTenancyMode() {
        String inviter = member("inviter-mode", TENANT_A, List.of("viewer"));
        String invitee = user("invitee-mode");
        AccessInviteResponse invite = create(POOLED, inviterIn(POOLED, inviter, partition(TENANT_A, 0)), request(invitee));
        assertEquals(PooledRealmTenantInvitePolicy.ID, invite.getAcceptancePolicy());
        acceptAnonymously(POOLED, invite, invitee);
        assertEquals(List.of(TENANT_A), assignment(invitee, POOLED).getAuthorizedTenantIds());
    }

    @Test
    void requestCannotPickAnotherPolicy() {
        String inviter = member("inviter-pick", TENANT_A, List.of("viewer"));
        AccessInviteRequest request = request(user("x"));
        request.setAcceptancePolicy(DedicatedRealmInvitePolicy.ID);
        assertRefused(Code.INVITE_POLICY_UNAVAILABLE,
            () -> create(POOLED, inviterIn(POOLED, inviter, partition(TENANT_A, 0)), request));
    }

    @Test
    void dedicatedPolicyRefusesAPooledRealm() {
        DedicatedRealmInvitePolicy dedicated = new DedicatedRealmInvitePolicy();
        Realm pooled = systemDirectory.findRealmByRefName(POOLED).orElseThrow();
        assertRefused(Code.INVITE_POLICY_UNAVAILABLE,
            () -> dedicated.validateCreate(new InviteCreation(pooled, null, null, false, false, null, null, null)));
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void registerPooledRealm(String refName) {
        asSystem(() -> {
            Realm realm = systemDirectory.findRealmByRefName(refName).orElseGet(() -> systemDirectory.registerRealm(Realm.builder()
                .refName(refName)
                .displayName(refName)
                .databaseName(refName)
                .emailDomain(refName + ".invite-test.example")
                .tenancyMode(RealmTenancyMode.MULTI_TENANT)
                .domainContext(DomainContext.builder()
                    .tenantId(TENANT_A).orgRefName("org-a").accountId("acct-a").defaultRealm(refName).build())
                .build()));
            ensureMembership(refName, TENANT_A, "org-a", "acct-a");
            ensureMembership(refName, TENANT_B, "org-b", "acct-b");
            return realm;
        });
    }

    private void ensureMembership(String realm, String tenantId, String org, String account) {
        if (realmMembershipService.tenantInRealm(realm, tenantId).isPresent()) {
            return;
        }
        realmMembershipService.upsertMembership(RealmTenantMembership.builder()
            .refName(org + "-" + realm)
            .displayName(org + " in " + realm)
            .realmRefName(realm)
            .tenantId(tenantId)
            .organizationRefName(org)
            .accountId(account)
            .membershipRole(TENANT_A.equals(tenantId) ? RealmTenantMembership.MEMBERSHIP_ROLE_OWNER : RealmTenantMembership.MEMBERSHIP_ROLE_PARTICIPANT)
            .participationStatus(RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE)
            .build());
    }

    private String user(String label) {
        String userId = label + "-" + suffix + "@pooled-invite-test.example";
        userIds.add(userId);
        return userId;
    }

    /** A user whose home realm is {@code realm} (default POOLED), assigned {@code tenant} with {@code roles}. */
    private String member(String label, String tenant, List<String> roles) {
        return member(label, tenant, roles, POOLED);
    }

    private String member(String label, String tenant, List<String> roles, String realm) {
        String userId = user(label);
        DomainContext home = DomainContext.builder().tenantId(tenant).defaultRealm(realm)
            .orgRefName(TENANT_A.equals(tenant) ? "org-a" : "org-b").accountId(TENANT_A.equals(tenant) ? "acct-a" : "acct-b").build();
        createAccount(userId, Set.of("user"), home);
        writeAssignment(userId, realm, roles, List.of(tenant), partition(tenant, 0));
        return userId;
    }

    private static DataDomain partition(String tenant, int dataSegment) {
        boolean a = TENANT_A.equals(tenant);
        return DataDomain.builder()
            .tenantId(tenant)
            .orgRefName(a ? "org-a" : "org-b")
            .accountNum(a ? "acct-a" : "acct-b")
            .dataSegment(dataSegment)
            .ownerId("unset")
            .build();
    }

    private static DomainContext homeIn(String tenant) {
        boolean a = TENANT_A.equals(tenant);
        return DomainContext.builder().tenantId(tenant).defaultRealm(POOLED)
            .orgRefName(a ? "org-a" : "org-b").accountId(a ? "acct-a" : "acct-b").build();
    }

    private static DataDomainPolicy policy(String key, DataDomainPolicyEntry.ResolutionMode mode, DataDomain fixed, String filter) {
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(mode);
        if (fixed != null) {
            entry.setDataDomains(List.of(fixed));
        }
        entry.setFilter(filter);
        DataDomainPolicy policy = new DataDomainPolicy();
        policy.setPolicyEntries(Map.of(key, entry));
        return policy;
    }

    /** The verified principal of a signed-in inviter in {@code realm} whose resolved partition is {@code domain}. */
    private PrincipalContext inviterIn(String realm, String userId, DataDomain domain) {
        return principalBuilder(realm, userId, domain).build();
    }

    private PrincipalContext.Builder principalBuilder(String realm, String userId, DataDomain domain) {
        DataDomain owned = PooledRealmTenantInvitePolicy.copy(domain, userId);
        // Admin principal roles only permit the policy-governed save; invite authority comes from
        // the inviter's credential and realm assignment.
        return new PrincipalContext.Builder()
            .withDefaultRealm(realm)
            .withDataDomain(owned)
            .withUserId(userId)
            .withScope("Authentication")
            .withRoles(new String[]{"admin", "user"});
    }

    private AccessInviteRequest request(String email, String... roles) {
        AccessInviteRequest request = new AccessInviteRequest();
        request.setEmail(email);
        request.setGrantedRoles(new ArrayList<>(List.of(roles)));
        return request;
    }

    private AccessInviteResponse create(String realm, PrincipalContext inviter, AccessInviteRequest request) {
        return as(inviter, () -> inviteService.createInvite(realm, inviter.getUserId(), request));
    }

    private AccessInviteAcceptResponse acceptAnonymously(String realm, AccessInviteResponse invite, String invitee) {
        AccessInviteAcceptRequest request = accept(realm, invite.getInviteToken());
        request.setPassword(PASSWORD);
        PrincipalContext anonymous = SecurityCallScope.anonymous(realm, testUtils.getTestDataDomain(), envConfigUtils.getAnonymousUserId());
        return SecurityCallScope.runWithContexts(anonymous, rContext, () -> inviteService.acceptInvite(request));
    }

    private AccessInviteAcceptRequest accept(String realm, String token) {
        assertNotNull(token);
        AccessInviteAcceptRequest request = new AccessInviteAcceptRequest();
        request.setRealm(realm);
        request.setToken(token);
        return request;
    }

    private PrincipalContext select(String userId, String headerTenant, String tokenTenant) {
        PrincipalContext signedIn = new PrincipalContext.Builder()
            .withDefaultRealm(POOLED)
            .withDataDomain(PooledRealmTenantInvitePolicy.copy(partition(TENANT_A, 0), userId))
            .withUserId(userId)
            .withScope("Authentication")
            .withRoles(new String[]{"user"})
            .build();
        return asSystem(() -> TenantSelectionProbe.select(systemDirectory, realmMembershipService, signedIn, headerTenant, tokenTenant));
    }

    private <T> T as(PrincipalContext principal, Supplier<T> action) {
        return SecurityCallScope.runWithContexts(principal, rContext, action);
    }

    /** Test fixtures are written as the framework system principal, like the service's own writes. */
    private <T> T asSystem(Supplier<T> action) {
        return SecurityCallScope.runWithContexts(securityUtils.getSystemPrincipalContext(), securityUtils.getSystemSecurityResourceContext(), action);
    }

    private void createAccount(String userId, Set<String> roles, DomainContext domainContext) {
        asSystem(() -> authProviderFactory.getUserManager().createUser(userId, PASSWORD, false, roles, domainContext));
    }

    private void writeAssignment(String userId, List<String> roles, List<String> tenants, DataDomain domain) {
        writeAssignment(userId, POOLED, roles, tenants, domain);
    }

    private void writeAssignment(String userId, String realm, List<String> roles, List<String> tenants, DataDomain domain) {
        asSystem(() -> {
            UserRealmRole assignment = realmMembershipService.realmsForUser(userId).stream()
                .filter(candidate -> realm.equals(candidate.getRealmRefName()))
                .findFirst()
                .orElseGet(() -> UserRealmRole.builder()
                    .refName("user-realm-role:" + userId + ":" + realm)
                    .userId(userId)
                    .realmRefName(realm)
                    .status(UserRealmRole.STATUS_ACTIVE)
                    .dataDomain(PooledRealmTenantInvitePolicy.copy(domain, userId))
                    .build());
            assignment.setRoles(new ArrayList<>(roles));
            assignment.setAuthorizedTenantIds(new ArrayList<>(tenants));
            return realmMembershipService.upsertUserRealmRole(assignment);
        });
    }

    private void writeApplications(String userId, String realm, List<String> applications) {
        asSystem(() -> {
            UserRealmRole assignment = realmMembershipService.realmsForUser(userId).stream()
                .filter(candidate -> realm.equals(candidate.getRealmRefName()))
                .findFirst()
                .orElseThrow();
            assignment.setAuthorizedApplications(new ArrayList<>(applications));
            return realmMembershipService.upsertUserRealmRole(assignment);
        });
    }

    private UserRealmRole assignment(String userId, String realm) {
        List<UserRealmRole> found = assignments(userId).stream().filter(a -> realm.equals(a.getRealmRefName())).toList();
        assertEquals(1, found.size(), "exactly one assignment per (user, realm)");
        return found.get(0);
    }

    private List<UserRealmRole> assignments(String userId) {
        try (var cursor = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm()).find(UserRealmRole.class)
            .filter(Filters.eq("userId", userId)).iterator()) {
            return cursor.toList();
        }
    }

    private Set<String> realmRoles(String userId, String realm) {
        Map<String, EnumSet<RoleSource>> sources = as(pContext, () -> identityRoleResolver.resolveRoleSources(userId, realm, null));
        Set<String> out = new LinkedHashSet<>();
        sources.forEach((role, from) -> {
            if (from.contains(RoleSource.REALM)) {
                out.add(role);
            }
        });
        return out;
    }

    private Optional<CredentialUserIdPassword> findCredential(String userId) {
        return as(pContext, () -> credentialRepo.findByUserId(userId, envConfigUtils.getSystemRealm(), true));
    }

    private CredentialUserIdPassword credential(String userId) {
        return findCredential(userId).orElseThrow();
    }

    private UserProfile profile(String realm, String userId) {
        try (var cursor = morphiaDataStoreWrapper.getDataStore(realm).find(UserProfile.class)
            .filter(Filters.eq("userId", userId)).iterator()) {
            List<UserProfile> found = cursor.toList();
            assertEquals(1, found.size());
            return found.get(0);
        }
    }

    private AccessInvite storedInvite(String realm, AccessInviteResponse invite) {
        try (var cursor = morphiaDataStoreWrapper.getDataStore(realm).find(AccessInvite.class)
            .filter(Filters.eq("refName", invite.getRefName())).iterator()) {
            return cursor.toList().get(0);
        }
    }

    private static void assertNoApplications(UserRealmRole assignment) {
        assertTrue(assignment.getAuthorizedApplications() == null || assignment.getAuthorizedApplications().isEmpty(),
            "no application was granted: " + assignment.getAuthorizedApplications());
    }

    private static void assertRefused(Code expected, Executable action) {
        AccessInviteException refused = assertThrows(AccessInviteException.class, action);
        assertEquals(expected, refused.getCode(), refused.getMessage());
    }
}
