package com.e2eq.framework.service.access;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.morphia.AccessInviteRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
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
import java.util.List;
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

    private String realm;
    private String suffix;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> inviteRefNames = new ArrayList<>();

    @BeforeEach
    void setUp() {
        realm = testUtils.getTestRealm();
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        String systemRealm = envConfigUtils.getSystemRealm();
        for (String userId : userIds) {
            morphiaDataStoreWrapper.getDataStore(systemRealm).find(CredentialUserIdPassword.class)
                .filter(Filters.eq("userId", userId)).delete(new dev.morphia.DeleteOptions().multi(true));
            morphiaDataStoreWrapper.getDataStore(realm).find(UserProfile.class)
                .filter(Filters.eq("userId", userId)).delete(new dev.morphia.DeleteOptions().multi(true));
        }
        for (String refName : inviteRefNames) {
            morphiaDataStoreWrapper.getDataStore(realm).find(AccessInvite.class)
                .filter(Filters.eq("refName", refName)).delete(new dev.morphia.DeleteOptions().multi(true));
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
        AccessInviteResponse revoked = as(principal(user("realm-admin"), "admin", "user"),
            () -> inviteService.revokeInvite(realm, invite.getRefName()));
        assertEquals("REVOKED", revoked.getStatus());
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

        List<AccessInviteResponse> admin = as(principal(user("realm-admin"), "admin"), () -> inviteService.listInvites(realm));
        assertTrue(admin.stream().anyMatch(i -> invite.getRefName().equals(i.getRefName())));
        assertTrue(admin.stream().allMatch(i -> i.getInviteToken() == null));

        assertRefused(Code.INVITE_NOT_AUTHORIZED,
            () -> as(principal(user("other"), "user"), () -> inviteService.getInvite(realm, invite.getRefName())));
    }

    @Test
    void inviterCannotGrantARoleItDoesNotHold() {
        String inviter = user("inviter");
        AccessInviteRequest request = new AccessInviteRequest();
        request.setEmail(user("escalate"));
        request.setGrantedRoles(List.of("admin"));
        assertRefused(Code.INVITE_ROLE_NOT_GRANTABLE,
            () -> as(principal(inviter, "user"), () -> inviteService.createInvite(realm, inviter, request)));
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

    private AccessInviteAcceptRequest accept(String token) {
        AccessInviteAcceptRequest request = new AccessInviteAcceptRequest();
        request.setRealm(realm);
        request.setToken(token);
        return request;
    }

    private void createAccount(String userId) {
        as(pContext, () -> authProviderFactory.getUserManager()
            .createUser(userId, PASSWORD, false, Set.of("user"), testUtils.getSystemDomainContext()));
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
