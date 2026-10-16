package com.e2eq.framework.service.access;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.RoleSource;
import com.e2eq.framework.model.persistent.morphia.AccessInviteRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.rest.requests.AccessInviteAcceptRequest;
import com.e2eq.framework.rest.requests.AccessInviteRequest;
import com.e2eq.framework.rest.responses.AccessInviteAcceptResponse;
import com.e2eq.framework.rest.responses.AccessInviteResponse;
import com.e2eq.framework.service.access.AccessInviteException.Code;
import com.e2eq.framework.util.EnvConfigUtils;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity binding and single-use guarantees of {@link AccessInviteService}. The account an invite
 * applies to always comes from the invite record, never from the acceptance request.
 */
@QuarkusTest
class AccessInviteServiceTest extends BaseRepoTest {

    private static final String PASSWORD = "Inv1te-Test-P@ssw0rd!";

    @Inject
    AccessInviteService inviteService;

    @Inject
    AccessInviteRepo inviteRepo;

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

    private String realm;
    /** A second, non-system realm the test inviters can be granted access to. */
    private String otherRealm;
    private String suffix;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> inviteRefNames = new ArrayList<>();

    @BeforeEach
    void setUp() {
        realm = testUtils.getTestRealm();
        otherRealm = testUtils.getDefaultRealm();
        // Invites are only ever created into, and accepted from, registered realms.
        as(pContext, () -> systemDirectory.findRealmByRefName(otherRealm).orElseGet(() -> systemDirectory.registerRealm(Realm.builder()
            .refName(otherRealm)
            .displayName(otherRealm)
            .databaseName(otherRealm)
            .emailDomain(testUtils.getDefaultDomainContext().getTenantId())
            .domainContext(testUtils.getDefaultDomainContext())
            .build())));
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        String systemRealm = envConfigUtils.getSystemRealm();
        for (String userId : userIds) {
            morphiaDataStoreWrapper.getDataStore(systemRealm).find(CredentialUserIdPassword.class)
                .filter(Filters.eq("userId", userId)).delete(new dev.morphia.DeleteOptions().multi(true));
            morphiaDataStoreWrapper.getDataStore(systemRealm).find(UserRealmRole.class)
                .filter(Filters.eq("userId", userId)).delete(new dev.morphia.DeleteOptions().multi(true));
            for (String r : List.of(realm, otherRealm)) {
                morphiaDataStoreWrapper.getDataStore(r).find(UserProfile.class)
                    .filter(Filters.eq("userId", userId)).delete(new dev.morphia.DeleteOptions().multi(true));
            }
        }
        for (String refName : inviteRefNames) {
            for (String r : List.of(realm, otherRealm)) {
                morphiaDataStoreWrapper.getDataStore(r).find(AccessInvite.class)
                    .filter(Filters.eq("refName", refName)).delete(new dev.morphia.DeleteOptions().multi(true));
            }
        }
        userIds.clear();
        inviteRefNames.clear();
    }

    // ---- acceptance: identity binding ------------------------------------------------------

    @Test
    void tokenHolderCannotChooseADifferentUserId() {
        String invitee = user("invitee");
        String victim = user("victim");
        AccessInviteResponse invite = createInvite(user("inviter"), invitee, null);

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setUserId(victim);
        request.setPassword(PASSWORD);

        assertRefused(Code.INVITE_IDENTITY_MISMATCH, () -> anonymous(() -> inviteService.acceptInvite(request)));
        assertTrue(findCredential(victim).isEmpty(), "no account may be created for a body-supplied userId");
        assertTrue(findCredential(invitee).isEmpty(), "the refused attempt must not provision the invitee either");
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    @Test
    void tokenHolderCannotChooseADifferentEmail() {
        String invitee = user("invitee");
        AccessInviteResponse invite = createInvite(user("inviter"), invitee, null);

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setEmail(user("someone-else"));
        request.setPassword(PASSWORD);

        assertRefused(Code.INVITE_IDENTITY_MISMATCH, () -> anonymous(() -> inviteService.acceptInvite(request)));
        assertTrue(findCredential(invitee).isEmpty());
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    @Test
    void existingUserWithoutSessionIsRefusedAndNoAccessIsAttached() {
        String existing = user("existing");
        createAccount(existing);
        AccessInviteResponse invite = createInvite(user("inviter"), existing, null);

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setPassword(PASSWORD);

        assertRefused(Code.INVITE_SIGN_IN_REQUIRED, () -> anonymous(() -> inviteService.acceptInvite(request)));
        assertFalse(hasRealm(findCredential(existing).orElseThrow()), "realm must not be attached without a session");
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    @Test
    void existingUserTargetedByUserIdWithoutSessionIsRefused() {
        String existing = user("existing-target");
        createAccount(existing);
        AccessInviteResponse invite = createInvite(user("inviter"), null, existing);

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        assertRefused(Code.INVITE_SIGN_IN_REQUIRED, () -> anonymous(() -> inviteService.acceptInvite(request)));
        assertFalse(hasRealm(findCredential(existing).orElseThrow()));
    }

    @Test
    void signedInAsAnotherUserIsRefused() {
        String existing = user("existing");
        String intruder = user("intruder");
        createAccount(existing);
        createAccount(intruder);
        AccessInviteResponse invite = createInvite(user("inviter"), existing, null);

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        assertRefused(Code.INVITE_IDENTITY_MISMATCH, () -> as(principal(intruder, "user"), () -> inviteService.acceptInvite(request)));
        assertFalse(hasRealm(findCredential(existing).orElseThrow()));
        assertFalse(hasRealm(findCredential(intruder).orElseThrow()));
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    @Test
    void sameUserSignedInIsAccepted() {
        String existing = user("existing");
        createAccount(existing);
        AccessInviteResponse invite = createInvite(user("inviter"), existing, null);

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        AccessInviteAcceptResponse response = as(principal(existing, "user"), () -> inviteService.acceptInvite(request));

        assertEquals(existing, response.getUserId());
        assertTrue(hasRealm(findCredential(existing).orElseThrow()));
        AccessInvite stored = reload(invite);
        assertEquals(AccessInvite.Status.ACCEPTED, stored.getStatus());
        assertEquals(existing, stored.getAcceptedUserId());
    }

    @Test
    void newUserIsCreatedForTheInvitedEmailOnly() {
        String invitee = user("new");
        AccessInviteResponse invite = createInvite(user("inviter"), invitee, null);

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setEmail(invitee.toUpperCase()); // an echo of the invited identity is tolerated
        request.setPassword(PASSWORD);
        request.setFirstName("Ada");
        AccessInviteAcceptResponse response = anonymous(() -> inviteService.acceptInvite(request));

        assertEquals(invitee, response.getUserId());
        assertEquals(invitee, response.getEmail());
        CredentialUserIdPassword credential = findCredential(invitee).orElseThrow();
        assertTrue(hasRealm(credential));
        assertEquals(AccessInvite.Status.ACCEPTED, reload(invite).getStatus());
    }

    @Test
    void newUserRequiresAPassword() {
        String invitee = user("new-nopw");
        AccessInviteResponse invite = createInvite(user("inviter"), invitee, null);

        assertRefused(Code.INVALID_REQUEST, () -> anonymous(() -> inviteService.acceptInvite(accept(invite.getInviteToken()))));
        assertTrue(findCredential(invitee).isEmpty());
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    // ---- acceptance: token handling --------------------------------------------------------

    @Test
    void tokenCannotBeReused() {
        String invitee = user("reuse");
        AccessInviteResponse invite = createInvite(user("inviter"), invitee, null);
        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setPassword(PASSWORD);
        anonymous(() -> inviteService.acceptInvite(request));

        assertRefused(Code.INVITE_NOT_PENDING, () -> anonymous(() -> inviteService.acceptInvite(request)));
        assertRefused(Code.INVITE_NOT_PENDING, () -> as(principal(invitee, "user"), () -> inviteService.acceptInvite(request)));
    }

    @Test
    void expiredTokenIsRefused() {
        String invitee = user("expired");
        AccessInviteResponse invite = createInvite(user("inviter"), invitee, null);
        morphiaDataStoreWrapper.getDataStore(realm).find(AccessInvite.class)
            .filter(Filters.eq("refName", invite.getRefName()))
            .update(UpdateOperators.set("expiresAt", new Date(System.currentTimeMillis() - 60_000)));

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setPassword(PASSWORD);
        assertRefused(Code.INVITE_EXPIRED, () -> anonymous(() -> inviteService.acceptInvite(request)));
        assertTrue(findCredential(invitee).isEmpty());
        assertEquals(AccessInvite.Status.EXPIRED, reload(invite).getStatus());
    }

    @Test
    void tamperedTokenIsRefused() {
        String invitee = user("tampered");
        AccessInviteResponse invite = createInvite(user("inviter"), invitee, null);
        String token = invite.getInviteToken();
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        AccessInviteAcceptRequest request = accept(tampered);
        request.setPassword(PASSWORD);
        assertRefused(Code.INVITE_TOKEN_INVALID, () -> anonymous(() -> inviteService.acceptInvite(request)));
        assertTrue(findCredential(invitee).isEmpty());
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    @Test
    void tokenIsStoredOnlyAsAHash() {
        AccessInviteResponse invite = createInvite(user("inviter"), user("hash"), null);
        AccessInvite stored = reload(invite);
        assertNotEquals(invite.getInviteToken(), stored.getTokenHash());
        String token = invite.getInviteToken();
        assertFalse(stored.getTokenHint() != null && token.contains(stored.getTokenHint()),
            "no fragment of the raw token may be persisted");
    }

    // ---- revoke / resend / list ------------------------------------------------------------

    @Test
    void revokeByNonInviterIsRefused() {
        String inviter = user("inviter");
        AccessInviteResponse invite = createInvite(inviter, user("rv"), null);

        assertRefused(Code.INVITE_NOT_AUTHORIZED,
            () -> as(principal(user("other"), "user"), () -> inviteService.revokeInvite(realm, invite.getRefName())));
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());

        AccessInviteResponse revoked = as(principal(inviter, "user"), () -> inviteService.revokeInvite(realm, invite.getRefName()));
        assertEquals("REVOKED", revoked.getStatus());
        assertRefused(Code.INVITE_NOT_PENDING,
            () -> as(principal(inviter, "user"), () -> inviteService.revokeInvite(realm, invite.getRefName())));
    }

    @Test
    void adminCanRevokeAnotherUsersInvite() {
        AccessInviteResponse invite = createInvite(user("inviter"), user("rv-admin"), null);
        String admin = realmAdmin();
        AccessInviteResponse revoked = as(principal(admin, "admin", "user"),
            () -> inviteService.revokeInvite(realm, invite.getRefName()));
        assertEquals("REVOKED", revoked.getStatus());
    }

    @Test
    void adminOfAnotherRealmIsNotAdminHere() {
        // Credential role "admin" whose home realm is not this realm (e.g. reached via X-Realm).
        String foreignAdmin = user("foreign-admin");
        createAccount(foreignAdmin, Set.of("admin", "user"), testUtils.getSystemDomainContext());
        AccessInviteResponse invite = createInvite(user("inviter"), user("xr"), null);
        PrincipalContext caller = principal(foreignAdmin, "admin", "user");

        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(caller, () -> inviteService.revokeInvite(realm, invite.getRefName())));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(caller, () -> inviteService.resendInvite(realm, invite.getRefName(), null)));
        assertRefused(Code.INVITE_NOT_AUTHORIZED, () -> as(caller, () -> inviteService.getInvite(realm, invite.getRefName())));
        assertTrue(as(caller, () -> inviteService.listInvites(realm)).stream()
            .noneMatch(i -> invite.getRefName().equals(i.getRefName())));
        AccessInviteRequest escalate = new AccessInviteRequest();
        escalate.setEmail(user("xr-escalate"));
        escalate.setGrantedRoles(List.of("admin"));
        // It cannot reach this realm at all, so it cannot invite into it (the X-Realm header is not trusted).
        assertRefused(Code.INVITE_REALM_NOT_ACCESSIBLE, () -> as(caller, () -> inviteService.createInvite(realm, foreignAdmin, escalate)));
    }

    @Test
    void realmAdminCannotGrantTheSystemRole() {
        String admin = realmAdmin();
        AccessInviteRequest request = new AccessInviteRequest();
        request.setEmail(user("sys"));
        request.setGrantedRoles(List.of("system"));
        assertRefused(Code.INVITE_ROLE_NOT_GRANTABLE,
            () -> as(principal(admin, "admin", "user"), () -> inviteService.createInvite(realm, admin, request)));
    }

    @Test
    void delegatedSessionCannotAccept() {
        String existing = user("delegated");
        createAccount(existing);
        AccessInviteResponse invite = createInvite(user("inviter"), existing, null);
        PrincipalContext delegated = SecurityCallScope.actingOnBehalfOf(principal(existing, "user"), user("agent"), null);

        assertRefused(Code.INVITE_IDENTITY_MISMATCH, () -> as(delegated, () -> inviteService.acceptInvite(accept(invite.getInviteToken()))));
        assertFalse(hasRealm(findCredential(existing).orElseThrow()));
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    @Test
    void existingAccountIsMatchedCaseInsensitively() {
        String lower = user("mixed");
        String stored = "Mixed-" + suffix + "@Invite-Test.example";
        userIds.add(stored);
        createAccount(stored);
        AccessInviteResponse invite = createInvite(user("inviter"), lower, null);

        AccessInviteAcceptRequest anonymousAttempt = accept(invite.getInviteToken());
        anonymousAttempt.setPassword(PASSWORD);
        assertRefused(Code.INVITE_SIGN_IN_REQUIRED, () -> anonymous(() -> inviteService.acceptInvite(anonymousAttempt)));

        AccessInviteAcceptResponse response = as(principal(stored, "user"), () -> inviteService.acceptInvite(accept(invite.getInviteToken())));
        assertEquals(stored, response.getUserId());
        assertTrue(hasRealm(findCredential(stored).orElseThrow()));
    }

    @Test
    void resendByNonInviterIsRefused() {
        String inviter = user("inviter");
        AccessInviteResponse invite = createInvite(inviter, user("rs"), null);
        String hashBefore = reload(invite).getTokenHash();

        assertRefused(Code.INVITE_NOT_AUTHORIZED,
            () -> as(principal(user("other"), "user"), () -> inviteService.resendInvite(realm, invite.getRefName(), null)));
        assertEquals(hashBefore, reload(invite).getTokenHash(), "a refused resend must not rotate the token");
    }

    @Test
    void resendByInviterRotatesTheToken() {
        String inviter = user("inviter");
        String invitee = user("rs-ok");
        AccessInviteResponse invite = createInvite(inviter, invitee, null);

        AccessInviteResponse resent = as(principal(inviter, "user"), () -> inviteService.resendInvite(realm, invite.getRefName(), null));
        assertNotNull(resent.getInviteToken());
        assertNotEquals(invite.getInviteToken(), resent.getInviteToken());

        AccessInviteAcceptRequest stale = accept(invite.getInviteToken());
        stale.setPassword(PASSWORD);
        assertRefused(Code.INVITE_TOKEN_INVALID, () -> anonymous(() -> inviteService.acceptInvite(stale)));

        AccessInviteAcceptRequest fresh = accept(resent.getInviteToken());
        fresh.setPassword(PASSWORD);
        assertEquals(invitee, anonymous(() -> inviteService.acceptInvite(fresh)).getUserId());
    }

    @Test
    void listShowsOnlyOwnInvitesAndNeverTokens() {
        String inviter = user("inviter");
        AccessInviteResponse invite = createInvite(inviter, user("ls"), null);

        List<AccessInviteResponse> other = as(principal(user("other"), "user"), () -> inviteService.listInvites(realm));
        assertTrue(other.stream().noneMatch(i -> invite.getRefName().equals(i.getRefName())));

        List<AccessInviteResponse> own = as(principal(inviter, "user"), () -> inviteService.listInvites(realm));
        AccessInviteResponse listed = own.stream().filter(i -> invite.getRefName().equals(i.getRefName())).findFirst().orElseThrow();
        assertNull(listed.getInviteToken());

        List<AccessInviteResponse> admin = as(principal(realmAdmin(), "admin"), () -> inviteService.listInvites(realm));
        assertTrue(admin.stream().anyMatch(i -> invite.getRefName().equals(i.getRefName())));
        assertTrue(admin.stream().allMatch(i -> i.getInviteToken() == null));

        assertRefused(Code.INVITE_NOT_AUTHORIZED,
            () -> as(principal(user("other"), "user"), () -> inviteService.getInvite(realm, invite.getRefName())));
    }

    @Test
    void inviterCannotGrantARoleItDoesNotHold() {
        String inviter = user("inviter");
        ensureAccount(inviter);
        AccessInviteRequest request = new AccessInviteRequest();
        request.setEmail(user("escalate"));
        request.setGrantedRoles(List.of("admin"));
        assertRefused(Code.INVITE_ROLE_NOT_GRANTABLE,
            () -> as(principal(inviter, "user"), () -> inviteService.createInvite(realm, inviter, request)));
    }


    // ---- realm-scoped invites (owner decision 2026-10-16) ----------------------------------

    @Test
    void inviteIntoARealmTheInviterCannotAccessIsRefused() {
        String inviter = user("no-access");
        ensureAccount(inviter); // home realm = this test realm; nothing else
        assertRefused(Code.INVITE_REALM_NOT_ACCESSIBLE, () -> createInviteIn(otherRealm, inviter, user("x"), List.of()));
        assertTrue(invitesFor(otherRealm, inviter).isEmpty());
    }

    @Test
    void nonSystemUserCannotInviteIntoTheSystemRealm() {
        String inviter = user("sys-realm");
        ensureAccount(inviter);
        // Even an explicit authorizedRealms entry and a '*' pattern never reach the system realm.
        setRealmAccess(inviter, List.of(envConfigUtils.getSystemRealm()), "*");
        assertRefused(Code.INVITE_REALM_NOT_ACCESSIBLE,
            () -> createInviteIn(envConfigUtils.getSystemRealm(), inviter, user("x"), List.of()));
    }

    @Test
    void inviterWithWildcardCanInviteIntoAnyNonSystemRealm() {
        String inviter = user("wildcard");
        createAccount(inviter, Set.of("user"), testUtils.getSystemDomainContext()); // no home-realm access to either realm
        setRealmAccess(inviter, null, "*");
        assertNotNull(createInviteIn(realm, inviter, user("w1"), List.of()).getRefName());
        assertNotNull(createInviteIn(otherRealm, inviter, user("w2"), List.of()).getRefName());
        assertRefused(Code.INVITE_REALM_NOT_ACCESSIBLE,
            () -> createInviteIn(envConfigUtils.getSystemRealm(), inviter, user("w3"), List.of()));
    }

    @Test
    void inviterWithRegexCanInviteOnlyIntoFullyMatchingRealms() {
        String inviter = user("regex");
        createAccount(inviter, Set.of("user"), testUtils.getSystemDomainContext());
        setRealmAccess(inviter, null, "TEST-QUANTUM-C.M"); // case-insensitive, full match
        assertNotNull(createInviteIn(realm, inviter, user("r1"), List.of()).getRefName());
        assertRefused(Code.INVITE_REALM_NOT_ACCESSIBLE, () -> createInviteIn(otherRealm, inviter, user("r2"), List.of()));

        // A pattern matching only part of the realm name grants nothing (anchored, full match).
        setRealmAccess(inviter, null, "quantum-com");
        assertRefused(Code.INVITE_REALM_NOT_ACCESSIBLE, () -> createInviteIn(realm, inviter, user("r3"), List.of()));
    }

    @Test
    void multiRealmOrWildcardRequestIsRefused() {
        String inviter = user("multi");
        ensureAccount(inviter);
        setRealmAccess(inviter, List.of(realm, otherRealm), null);

        AccessInviteRequest several = inviteRequest(user("m1"), List.of());
        several.setRealms(List.of(realm, otherRealm));
        assertRefused(Code.INVITE_SINGLE_REALM_REQUIRED, () -> createInviteAs(realm, inviter, several));

        AccessInviteRequest wildcard = inviteRequest(user("m2"), List.of());
        wildcard.setRealm("*");
        assertRefused(Code.INVITE_SINGLE_REALM_REQUIRED, () -> createInviteAs(realm, inviter, wildcard));

        AccessInviteRequest elsewhere = inviteRequest(user("m3"), List.of());
        elsewhere.setRealm(otherRealm);
        assertRefused(Code.INVITE_SINGLE_REALM_REQUIRED, () -> createInviteAs(realm, inviter, elsewhere));

        assertRefused(Code.INVITE_SINGLE_REALM_REQUIRED, () -> createInviteAs("*", inviter, inviteRequest(user("m4"), List.of())));
        assertTrue(invitesFor(realm, inviter).isEmpty());

        // Repeating the request realm is fine.
        AccessInviteRequest same = inviteRequest(user("m5"), List.of());
        same.setRealms(List.of(realm.toUpperCase()));
        assertNotNull(createInviteAs(realm, inviter, same).getRefName());
    }

    @Test
    void newUsersInvitedRolesAreRealmScopedNotFlat() {
        String inviter = user("grantor");
        createAccount(inviter, Set.of("user", "viewer"), testUtils.getTestDomainContext());
        String invitee = user("scoped-new");
        AccessInviteResponse invite = createInviteIn(realm, inviter, invitee, List.of("viewer"));

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setPassword(PASSWORD);
        anonymous(() -> inviteService.acceptInvite(request));

        CredentialUserIdPassword credential = findCredential(invitee).orElseThrow();
        assertEquals(List.of("user"), List.of(credential.getRoles()), "invited roles must never land on flat roles");
        List<UserRealmRole> assignments = assignments(invitee);
        assertEquals(1, assignments.size());
        assertEquals(realm, assignments.get(0).getRealmRefName());
        assertEquals(List.of("viewer"), assignments.get(0).getRoles());
        assertEquals(UserRealmRole.STATUS_ACTIVE, assignments.get(0).getStatus());
        assertTrue(realmRoles(invitee, otherRealm).isEmpty(), "the roles apply in the invited realm only");
    }

    @Test
    void existingUserGetsAnAssignmentAndAcceptingAgainMergesWithoutDuplicating() {
        String inviter = user("grantor");
        createAccount(inviter, Set.of("user", "viewer", "editor"), testUtils.getTestDomainContext());
        String existing = user("scoped-existing");
        createAccount(existing);

        AccessInviteResponse first = createInviteIn(realm, inviter, existing, List.of("viewer"));
        as(principal(existing, "user"), () -> inviteService.acceptInvite(accept(first.getInviteToken())));
        assertEquals(List.of("viewer"), assignments(existing).get(0).getRoles());
        assertTrue(hasRealm(findCredential(existing).orElseThrow()));
        assertEquals(List.of("user"), List.of(findCredential(existing).orElseThrow().getRoles()));

        // The same token cannot be replayed, and a second invite for the same realm merges by union.
        assertRefused(Code.INVITE_NOT_PENDING,
            () -> as(principal(existing, "user"), () -> inviteService.acceptInvite(accept(first.getInviteToken()))));
        AccessInviteResponse second = createInviteIn(realm, inviter, existing, List.of("viewer", "editor"));
        as(principal(existing, "user"), () -> inviteService.acceptInvite(accept(second.getInviteToken())));

        List<UserRealmRole> assignments = assignments(existing);
        assertEquals(1, assignments.size(), "one assignment per (userId, realm)");
        assertEquals(Set.of("viewer", "editor"), Set.copyOf(assignments.get(0).getRoles()));
        assertEquals(2, assignments.get(0).getRoles().size());
        long authorizedEntries = findCredential(existing).orElseThrow().getAuthorizedRealms().stream()
            .filter(entry -> realm.equalsIgnoreCase(entry.getRealmRefName())).count();
        assertEquals(1, authorizedEntries);
    }

    @Test
    void invitesIntoTwoRealmsGiveSeparateAssignmentsSeenOnlyInTheirRealm() {
        String inviter = user("two-realm");
        createAccount(inviter, Set.of("user", "viewer", "editor"), testUtils.getTestDomainContext());
        setRealmAccess(inviter, List.of(realm, otherRealm), null);
        String existing = user("two-realm-invitee");
        createAccount(existing);

        AccessInviteResponse inA = createInviteIn(realm, inviter, existing, List.of("viewer"));
        AccessInviteResponse inB = createInviteIn(otherRealm, inviter, existing, List.of("editor"));
        as(principal(existing, "user"), () -> inviteService.acceptInvite(accept(inA.getInviteToken())));
        AccessInviteAcceptRequest acceptB = accept(inB.getInviteToken());
        acceptB.setRealm(otherRealm);
        as(principal(existing, "user"), () -> inviteService.acceptInvite(acceptB));

        assertEquals(2, assignments(existing).size());
        Set<String> rolesInA = realmRoles(existing, realm);
        Set<String> rolesInB = realmRoles(existing, otherRealm);
        assertEquals(Set.of("viewer"), rolesInA);
        assertEquals(Set.of("editor"), rolesInB);

        // Through the resolver: beyond the flat roles, each realm sees only its own invited roles.
        Set<String> resolvedA = as(pContext, () -> identityRoleResolver.resolveRoleSources(existing, realm, null).keySet());
        Set<String> resolvedB = as(pContext, () -> identityRoleResolver.resolveRoleSources(existing, otherRealm, null).keySet());
        assertTrue(resolvedA.contains("viewer") && !resolvedA.contains("editor"), resolvedA.toString());
        assertTrue(resolvedB.contains("editor") && !resolvedB.contains("viewer"), resolvedB.toString());
        assertTrue(resolvedA.contains("user") && resolvedB.contains("user"), "flat roles apply in every realm");
    }

    @Test
    void roleGrantCheckUsesTheInvitersRolesInThatRealm() {
        String inviter = user("realm-grantor");
        ensureAccount(inviter); // flat roles: user
        setRealmAccess(inviter, List.of(realm, otherRealm), null);
        writeAssignment(inviter, realm, List.of("approver"));

        // Holds "approver" only in this realm: grantable here, not in the other realm.
        assertNotNull(createInviteIn(realm, inviter, user("g1"), List.of("approver")).getRefName());
        assertRefused(Code.INVITE_ROLE_NOT_GRANTABLE, () -> createInviteIn(otherRealm, inviter, user("g2"), List.of("approver")));
        // Flat roles count in every realm the inviter can access.
        assertNotNull(createInviteIn(otherRealm, inviter, user("g3"), List.of("user")).getRefName());
        assertRefused(Code.INVITE_ROLE_NOT_GRANTABLE, () -> createInviteIn(realm, inviter, user("g4"), List.of("system")));
    }

    @Test
    void inviterThatLostTheRoleCannotHaveItsInviteAccepted() {
        String inviter = user("lapsed");
        ensureAccount(inviter);
        writeAssignment(inviter, realm, List.of("approver"));
        String invitee = user("lapsed-invitee");
        AccessInviteResponse invite = createInviteIn(realm, inviter, invitee, List.of("approver"));
        writeAssignment(inviter, realm, List.of());

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setPassword(PASSWORD);
        assertRefused(Code.INVITE_ROLE_NOT_GRANTABLE, () -> anonymous(() -> inviteService.acceptInvite(request)));
        assertTrue(findCredential(invitee).isEmpty());
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    @Test
    void invitedApplicationsAreRecordedOnTheRealmAssignment() {
        String admin = realmAdmin();
        String invitee = user("apps");
        AccessInviteRequest request = inviteRequest(invitee, List.of());
        request.setAuthorizedApplications(List.of("app-a"));
        AccessInviteResponse invite = createInviteAs(realm, admin, request);

        AccessInviteAcceptRequest acceptRequest = accept(invite.getInviteToken());
        acceptRequest.setPassword(PASSWORD);
        anonymous(() -> inviteService.acceptInvite(acceptRequest));
        assertEquals(List.of("app-a"), assignments(invitee).get(0).getAuthorizedApplications());

        // A non-admin cannot hand out an application it has no grant for in this realm.
        String inviter = user("apps-inviter");
        ensureAccount(inviter);
        AccessInviteRequest denied = inviteRequest(user("apps-2"), List.of());
        denied.setAuthorizedApplications(List.of("app-a"));
        assertRefused(Code.INVITE_APPLICATION_NOT_GRANTABLE, () -> createInviteAs(realm, inviter, denied));
    }


    // ---- security review fixes -------------------------------------------------------------

    @Test
    void explicitRealmListTakesPrecedenceOverRealmRegEx() {
        String inviter = user("list-and-regex");
        createAccount(inviter, Set.of("user"), testUtils.getSystemDomainContext());
        setRealmAccess(inviter, List.of(realm), "*"); // as at sign-in, the list wins; '*' is ignored
        assertNotNull(createInviteIn(realm, inviter, user("lr1"), List.of()).getRefName());
        assertRefused(Code.INVITE_REALM_NOT_ACCESSIBLE, () -> createInviteIn(otherRealm, inviter, user("lr2"), List.of()));
    }

    @Test
    void unregisteredRealmIsRefused() {
        String inviter = user("unregistered");
        ensureAccount(inviter);
        setRealmAccess(inviter, null, "*");
        assertRefused(Code.INVITE_REALM_NOT_ACCESSIBLE, () -> createInviteIn("no-such-realm-" + suffix, inviter, user("u1"), List.of()));

        AccessInviteAcceptRequest request = accept("any-token");
        request.setRealm("no-such-realm-" + suffix);
        assertRefused(Code.INVITE_TOKEN_INVALID, () -> anonymous(() -> inviteService.acceptInvite(request)));
    }

    @Test
    void invitingAnExistingAccountCannotCarryFlatRolesTheInviterDoesNotHold() {
        // A member holding only "user" here invites an account that is admin elsewhere. Attaching this
        // realm would make that account's flat admin apply here, so acceptance is refused.
        String inviter = user("plain-member");
        ensureAccount(inviter);
        String foreignAdmin = user("foreign-admin-invitee");
        createAccount(foreignAdmin, Set.of("admin", "user"), testUtils.getSystemDomainContext());
        AccessInviteResponse invite = createInvite(inviter, null, foreignAdmin);

        assertRefused(Code.INVITE_ROLE_NOT_GRANTABLE,
            () -> as(principal(foreignAdmin, "user"), () -> inviteService.acceptInvite(accept(invite.getInviteToken()))));
        assertFalse(hasRealm(findCredential(foreignAdmin).orElseThrow()));
        assertTrue(assignments(foreignAdmin).isEmpty());
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());

        // A realm administrator holds admin here and may bring that account in.
        String admin = realmAdmin();
        AccessInviteRequest byAdmin = new AccessInviteRequest();
        byAdmin.setTargetUserId(foreignAdmin);
        as(principal(admin, "admin", "user"), () -> inviteService.revokeInvite(realm, invite.getRefName()));
        AccessInviteResponse adminInvite = createInviteAs(realm, admin, byAdmin);
        as(principal(foreignAdmin, "user"), () -> inviteService.acceptInvite(accept(adminInvite.getInviteToken())));
        assertTrue(hasRealm(findCredential(foreignAdmin).orElseThrow()));
    }

    @Test
    void failedAcceptanceOfANewUserLeavesNoAccountOrAssignment() {
        String inviter = user("grantor");
        createAccount(inviter, Set.of("user", "viewer"), testUtils.getTestDomainContext());
        String invitee = user("rollback-new");
        AccessInviteRequest request = inviteRequest(invitee, List.of("viewer"));
        request.setInviteMessage(FailingTestInviteProvisioner.FAIL_MARKER);
        AccessInviteResponse invite = createInviteAs(realm, inviter, request);

        AccessInviteAcceptRequest acceptRequest = accept(invite.getInviteToken());
        acceptRequest.setPassword(PASSWORD);
        assertRefused(Code.INVITE_PROVISIONING_FAILED, () -> anonymous(() -> inviteService.acceptInvite(acceptRequest)));
        assertTrue(findCredential(invitee).isEmpty(), "the account created for the invite is removed");
        assertTrue(assignments(invitee).isEmpty(), "the realm assignment is removed");
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    @Test
    void failedAcceptanceOfAnExistingUserRestoresItsAccess() {
        String inviter = user("grantor");
        createAccount(inviter, Set.of("user", "viewer", "editor"), testUtils.getTestDomainContext());
        String existing = user("rollback-existing");
        createAccount(existing);
        writeAssignment(existing, realm, List.of("viewer"));
        AccessInviteRequest request = new AccessInviteRequest();
        request.setTargetUserId(existing);
        request.setGrantedRoles(List.of("editor"));
        request.setInviteMessage(FailingTestInviteProvisioner.FAIL_MARKER);
        AccessInviteResponse invite = createInviteAs(realm, inviter, request);

        assertRefused(Code.INVITE_PROVISIONING_FAILED,
            () -> as(principal(existing, "user"), () -> inviteService.acceptInvite(accept(invite.getInviteToken()))));
        assertEquals(List.of("viewer"), assignments(existing).get(0).getRoles(), "the prior assignment is restored");
        assertFalse(hasRealm(findCredential(existing).orElseThrow()), "the realm entry added by the attempt is removed");
        assertEquals(AccessInvite.Status.PENDING, reload(invite).getStatus());
    }

    @Test
    void acceptingDoesNotSilentlyShadowAnAccountsRealmRegEx() {
        String inviter = user("grantor");
        ensureAccount(inviter);
        setRealmAccess(inviter, List.of(realm, otherRealm), null);
        String existing = user("regex-account");
        createAccount(existing, Set.of("user"), testUtils.getSystemDomainContext());
        setRealmAccess(existing, null, "TEST-QUANTUM-C.M");
        AccessInviteResponse invite = createInviteIn(otherRealm, inviter, existing, List.of());

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setRealm(otherRealm);
        assertRefused(Code.INVITE_ACCOUNT_REALM_CONFLICT, () -> as(principal(existing, "user"), () -> inviteService.acceptInvite(request)));
        CredentialUserIdPassword credential = findCredential(existing).orElseThrow();
        assertTrue(credential.getAuthorizedRealms() == null || credential.getAuthorizedRealms().isEmpty());

        // When the pattern already grants the realm, accepting adds no list entry that would shadow it.
        AccessInviteResponse inMatching = createInviteIn(realm, inviter, existing, List.of());
        as(principal(existing, "user"), () -> inviteService.acceptInvite(accept(inMatching.getInviteToken())));
        CredentialUserIdPassword after = findCredential(existing).orElseThrow();
        assertTrue(after.getAuthorizedRealms() == null || after.getAuthorizedRealms().isEmpty());
        assertEquals(1, assignments(existing).size());
    }

    // ---- helpers ---------------------------------------------------------------------------

    private String user(String label) {
        String userId = label + "-" + suffix + "@invite-test.example";
        userIds.add(userId);
        return userId;
    }

    private PrincipalContext principal(String userId, String... roles) {
        return testUtils.getTestPrincipalContext(userId, roles);
    }

    private <T> T as(PrincipalContext principal, Supplier<T> action) {
        return SecurityCallScope.runWithContexts(principal, rContext, action);
    }

    private <T> T anonymous(Supplier<T> action) {
        PrincipalContext anonymous = SecurityCallScope.anonymous(realm, testUtils.getTestDataDomain(), envConfigUtils.getAnonymousUserId());
        return SecurityCallScope.runWithContexts(anonymous, rContext, action);
    }

    private AccessInviteResponse createInvite(String inviter, String email, String targetUserId) {
        ensureAccount(inviter);
        AccessInviteRequest request = new AccessInviteRequest();
        request.setEmail(email);
        request.setTargetUserId(targetUserId);
        // The inviter needs persistence permission for the invite itself (policy-governed save);
        // the authorization rules under test below are checked by the service independently of it.
        PrincipalContext saver = testUtils.getDefaultPrincipalContext(inviter, new String[]{"admin", "user"});
        AccessInviteResponse response = as(saver, () -> inviteService.createInvite(realm, inviter, request));
        inviteRefNames.add(response.getRefName());
        assertNotNull(response.getInviteToken());
        return response;
    }


    private AccessInviteRequest inviteRequest(String email, List<String> roles) {
        AccessInviteRequest request = new AccessInviteRequest();
        request.setEmail(email);
        request.setGrantedRoles(new ArrayList<>(roles));
        return request;
    }

    private AccessInviteResponse createInviteIn(String inviteRealm, String inviter, String email, List<String> roles) {
        return createInviteAs(inviteRealm, inviter, inviteRequest(email, roles));
    }

    /**
     * Creates an invite as {@code inviter}. The principal carries admin roles only so the policy-governed
     * save is permitted; realm access and grantable roles come from the inviter's credential.
     */
    private AccessInviteResponse createInviteAs(String inviteRealm, String inviter, AccessInviteRequest request) {
        PrincipalContext saver = testUtils.getDefaultPrincipalContext(inviter, new String[]{"admin", "user"});
        AccessInviteResponse response = as(saver, () -> inviteService.createInvite(inviteRealm, inviter, request));
        inviteRefNames.add(response.getRefName());
        return response;
    }

    /** Creates a credential (home realm = this test realm, flat role user) unless one exists. */
    private void ensureAccount(String userId) {
        if (findCredential(userId).isEmpty()) {
            createAccount(userId, Set.of("user"), testUtils.getTestDomainContext());
        }
    }

    private void setRealmAccess(String userId, List<String> authorizedRealms, String realmRegEx) {
        as(pContext, () -> {
            CredentialUserIdPassword credential = credentialRepo.findByUserId(userId, envConfigUtils.getSystemRealm(), true).orElseThrow();
            List<CredentialUserIdPassword.RealmEntry> entries = new ArrayList<>();
            if (authorizedRealms != null) {
                for (String r : authorizedRealms) {
                    CredentialUserIdPassword.RealmEntry entry = new CredentialUserIdPassword.RealmEntry();
                    entry.setRealmRefName(r);
                    entry.setRealmDisplayName(r);
                    entries.add(entry);
                }
            }
            credential.setAuthorizedRealms(entries);
            credential.setRealmRegEx(realmRegEx);
            return credentialRepo.save(envConfigUtils.getSystemRealm(), credential);
        });
    }

    private void writeAssignment(String userId, String assignmentRealm, List<String> roles) {
        as(pContext, () -> {
            UserRealmRole assignment = realmMembershipService.realmsForUser(userId).stream()
                .filter(candidate -> assignmentRealm.equals(candidate.getRealmRefName()))
                .findFirst()
                .orElseGet(() -> UserRealmRole.builder()
                    .refName("user-realm-role:" + userId + ":" + assignmentRealm)
                    .userId(userId)
                    .realmRefName(assignmentRealm)
                    .status(UserRealmRole.STATUS_ACTIVE)
                    .dataDomain(testUtils.getTestDataDomain())
                    .build());
            assignment.setRoles(new ArrayList<>(roles));
            return realmMembershipService.upsertUserRealmRole(assignment);
        });
    }

    private List<UserRealmRole> assignments(String userId) {
        try (var cursor = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm()).find(UserRealmRole.class)
            .filter(Filters.eq("userId", userId)).iterator()) {
            return cursor.toList();
        }
    }

    /** Roles a user holds through its UserRealmRole for one realm (excludes flat credential roles). */
    private Set<String> realmRoles(String userId, String assignmentRealm) {
        Map<String, EnumSet<RoleSource>> sources = as(pContext, () -> identityRoleResolver.resolveRoleSources(userId, assignmentRealm, null));
        Set<String> out = new java.util.LinkedHashSet<>();
        sources.forEach((role, from) -> {
            if (from.contains(RoleSource.REALM)) {
                out.add(role);
            }
        });
        return out;
    }

    private List<AccessInvite> invitesFor(String inviteRealm, String inviter) {
        try (var cursor = morphiaDataStoreWrapper.getDataStore(inviteRealm).find(AccessInvite.class)
            .filter(Filters.eq("invitedByUserId", inviter)).iterator()) {
            return cursor.toList();
        }
    }

    private AccessInviteAcceptRequest accept(String token) {
        AccessInviteAcceptRequest request = new AccessInviteAcceptRequest();
        request.setRealm(realm);
        request.setToken(token);
        return request;
    }

    private void createAccount(String userId) {
        createAccount(userId, Set.of("user"), testUtils.getSystemDomainContext());
    }

    private void createAccount(String userId, Set<String> roles, com.e2eq.framework.model.security.DomainContext domainContext) {
        as(pContext, () -> authProviderFactory.getUserManager()
            .createUser(userId, PASSWORD, false, roles, domainContext));
    }

    /** An administrator whose credential home realm is this test realm. */
    private String realmAdmin() {
        String admin = user("realm-admin");
        createAccount(admin, Set.of("admin", "user"), testUtils.getTestDomainContext());
        return admin;
    }

    private Optional<CredentialUserIdPassword> findCredential(String userId) {
        return as(pContext, () -> credentialRepo.findByUserId(userId, envConfigUtils.getSystemRealm(), true));
    }

    private boolean hasRealm(CredentialUserIdPassword credential) {
        return credential.getAuthorizedRealms() != null && credential.getAuthorizedRealms().stream()
            .anyMatch(entry -> entry != null && realm.equalsIgnoreCase(entry.getRealmRefName()));
    }

    private AccessInvite reload(AccessInviteResponse invite) {
        return as(pContext, () -> inviteRepo.findByRefNameIgnoreRules(realm, invite.getRefName()).orElseThrow());
    }

    private static void assertRefused(Code expected, Executable action) {
        AccessInviteException refused = assertThrows(AccessInviteException.class, action);
        assertEquals(expected, refused.getCode(), refused.getMessage());
    }
}
