package com.e2eq.framework.service.access;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.morphia.AccessInviteRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.rest.requests.AccessInviteAcceptRequest;
import com.e2eq.framework.rest.requests.AccessInviteRequest;
import com.e2eq.framework.rest.responses.AccessInviteAcceptResponse;
import com.e2eq.framework.rest.responses.AccessInviteResponse;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invite acceptance with application-scoped policy evaluation required
 * ({@code quantum.security.policy-scope.require-application=true}, as Quantum Auth runs). The
 * acceptance's own work on the issuer's records (credential, realm assignment, profile) runs as the
 * system principal in the issuer's application, so an anonymous new-account acceptance, which names
 * no application, is not refused with {@code APPLICATION_SCOPE_REQUIRED}.
 */
@QuarkusTest
@TestProfile(AccessInviteRequireApplicationTest.RequireApplicationProfile.class)
class AccessInviteRequireApplicationTest extends BaseRepoTest {

    static final String CALLING_APPLICATION = "invite-console";
    private static final String PASSWORD = "Inv1te-Test-P@ssw0rd!";

    public static class RequireApplicationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quantum.security.policy-scope.require-application", "true",
                "quantum.migration.application-id", CALLING_APPLICATION);
        }
    }

    @Inject AccessInviteService inviteService;
    @Inject AccessInviteRepo inviteRepo;
    @Inject CredentialRepo credentialRepo;
    @Inject AuthProviderFactory authProviderFactory;
    @Inject EnvConfigUtils envConfigUtils;
    @Inject MorphiaDataStoreWrapper morphiaDataStoreWrapper;
    @Inject SecurityUtils securityUtils;

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
            morphiaDataStoreWrapper.getDataStore(systemRealm).find(UserRealmRole.class)
                .filter(Filters.eq("userId", userId)).delete(new dev.morphia.DeleteOptions().multi(true));
            for (String r : List.of(realm, systemRealm)) {
                morphiaDataStoreWrapper.getDataStore(r).find(UserProfile.class)
                    .filter(Filters.eq("userId", userId)).delete(new dev.morphia.DeleteOptions().multi(true));
            }
        }
        for (String refName : inviteRefNames) {
            morphiaDataStoreWrapper.getDataStore(realm).find(AccessInvite.class)
                .filter(Filters.eq("refName", refName)).delete(new dev.morphia.DeleteOptions().multi(true));
        }
        userIds.clear();
        inviteRefNames.clear();
    }

    @Test
    void aNewAccountAcceptsAnInviteAnonymously() {
        String invitee = user("new");
        AccessInviteResponse invite = createInvite(user("inviter"), invitee);

        AccessInviteAcceptRequest request = accept(invite.getInviteToken());
        request.setPassword(PASSWORD);
        request.setFirstName("Ada");
        // A public (@PermitAll) acceptance: the anonymous caller names no application.
        AccessInviteAcceptResponse response = anonymous(() -> inviteService.acceptInvite(request));

        assertEquals(invitee, response.getUserId());
        CredentialUserIdPassword credential = findCredential(invitee).orElseThrow();
        assertTrue(hasRealm(credential), "the new account must be granted the invite's realm");
        assertEquals(AccessInvite.Status.ACCEPTED, reload(invite).getStatus());
    }

    @Test
    void anExistingSignedInAccountAcceptsAnInvite() {
        String existing = user("existing");
        createAccount(existing, Set.of("user"), testUtils.getSystemDomainContext());
        AccessInviteResponse invite = createInvite(user("inviter"), existing);

        AccessInviteAcceptResponse response = as(withApplication(principal(existing, "user")),
            () -> inviteService.acceptInvite(accept(invite.getInviteToken())));

        assertEquals(existing, response.getUserId());
        assertTrue(hasRealm(findCredential(existing).orElseThrow()));
        assertEquals(AccessInvite.Status.ACCEPTED, reload(invite).getStatus());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private String user(String label) {
        String userId = label + "-" + suffix + "@invite-app-scope.example";
        userIds.add(userId);
        return userId;
    }

    private AccessInviteResponse createInvite(String inviter, String email) {
        if (findCredential(inviter).isEmpty()) {
            createAccount(inviter, Set.of("user"), testUtils.getTestDomainContext());
        }
        AccessInviteRequest request = new AccessInviteRequest();
        request.setEmail(email);
        PrincipalContext saver = withApplication(testUtils.getDefaultPrincipalContext(inviter, new String[]{"admin", "user"}));
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

    private void createAccount(String userId, Set<String> roles, com.e2eq.framework.model.security.DomainContext domainContext) {
        asSystemInApplication(() -> authProviderFactory.getUserManager()
            .createUser(userId, PASSWORD, false, roles, domainContext));
    }

    private Optional<CredentialUserIdPassword> findCredential(String userId) {
        return asSystemInApplication(() -> credentialRepo.findByUserIdIgnoreCaseIgnoreRules(userId, envConfigUtils.getSystemRealm()));
    }

    private boolean hasRealm(CredentialUserIdPassword credential) {
        return credential.getAuthorizedRealms() != null && credential.getAuthorizedRealms().stream()
            .anyMatch(entry -> entry != null && realm.equalsIgnoreCase(entry.getRealmRefName()));
    }

    private AccessInvite reload(AccessInviteResponse invite) {
        return asSystemInApplication(() -> inviteRepo.findByRefNameIgnoreRules(realm, invite.getRefName()).orElseThrow());
    }

    private PrincipalContext principal(String userId, String... roles) {
        return testUtils.getTestPrincipalContext(userId, roles);
    }

    private <T> T as(PrincipalContext principal, Supplier<T> action) {
        ResourceContext resource = new ResourceContext.Builder()
            .withRealm(realm)
            .withApplicationId(principal.getApplicationId())
            .withArea(rContext.getArea())
            .withFunctionalDomain(rContext.getFunctionalDomain())
            .withAction(rContext.getAction())
            .build();
        return SecurityCallScope.runWithContexts(principal, resource, action);
    }

    private <T> T anonymous(Supplier<T> action) {
        PrincipalContext anonymous = SecurityCallScope.anonymous(realm, testUtils.getTestDataDomain(), envConfigUtils.getAnonymousUserId());
        return SecurityCallScope.runWithContexts(anonymous, rContext, action);
    }

    private <T> T asSystemInApplication(Supplier<T> action) {
        return as(withApplication(securityUtils.getSystemPrincipalContext()), action);
    }

    private static PrincipalContext withApplication(PrincipalContext base) {
        return new PrincipalContext.Builder()
            .withDefaultRealm(base.getDefaultRealm())
            .withApplicationId(CALLING_APPLICATION)
            .withDomainContext(base.getDomainContext())
            .withDataDomain(base.getDataDomain())
            .withUserId(base.getUserId())
            .withRoles(base.getRoles())
            .withScope(base.getScope())
            .build();
    }
}
