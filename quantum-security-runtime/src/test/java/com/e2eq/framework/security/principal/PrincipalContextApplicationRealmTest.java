package com.e2eq.framework.security.principal;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity-only resolution of a request that names no realm: the realm comes from the
 * application (the realms it owns that the identity may use), not the identity's home realm.
 */
class PrincipalContextApplicationRealmTest {

    private static final String DI = "di-app";

    private final PrincipalFixture world = new PrincipalFixture();

    /** alice (home acme-D1) is a member of the shared realm di-shared, owned by di-app. */
    @BeforeEach
    void sharedRealmOwnedByAnotherApplication() {
        own(world.realm("di-shared", RealmTenancyMode.MULTI_TENANT, "di-shared", "di-org", "di-acct"), DI);
        world.member("di-shared", "airfreight", "air-org", "air-acct");
        UserRealmRole assignment = world.assign("alice@acme", "di-shared", List.of("di-user"));
        assignment.setAuthorizedTenantIds(List.of("airfreight"));
        authorize("alice@acme", "di-shared");
    }

    @Test
    void aSharedRealmApplicationResolvesToItsRealmForAnIdentityHomedElsewhere() {
        PrincipalContext context = resolve(DI, null);
        assertEquals("di-shared", context.getDefaultRealm());
        assertEquals("airfreight", context.getDataDomain().getTenantId());
        assertEquals("air-org", context.getDataDomain().getOrgRefName());
        assertTrue(List.of(context.getRoles()).contains("di-user"));
    }

    @Test
    void anApplicationThatOwnsTheHomeRealmStaysInTheHomeRealm() {
        assertEquals("acme-D1", resolve(PrincipalFixture.APP, null).getDefaultRealm());
    }

    @Test
    void anApplicationThatOwnsNoUsableRealmIsAdmittedOrRefusedInTheHomeRealm() {
        world.assignments.get("alice@acme|acme-D1").setAuthorizedApplications(List.of("other-app"));
        assertEquals("acme-D1", resolve("other-app", null).getDefaultRealm());

        world.assignments.get("alice@acme|acme-D1").setAuthorizedApplications(null);
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve("other-app", null));
    }

    @Test
    void severalUsableRealmsRequireAnExplicitRealm() {
        own(world.realm("di-two", RealmTenancyMode.SINGLE_TENANT, "di-two", "di-two-org", "di-two-acct"), DI);
        world.assign("alice@acme", "di-two", List.of("di-two-user"));
        authorize("alice@acme", "di-two");

        PrincipalResolutionException refusal =
                refused(PrincipalResolutionError.REALM_NOT_AUTHORIZED, () -> resolve(DI, null));
        assertTrue(refusal.getMessage().contains("di-shared") && refusal.getMessage().contains("di-two"),
                refusal.getMessage());
        assertEquals("di-two", resolve(DI, "di-two").getDefaultRealm());
    }

    @Test
    void anOwnedRealmOutsideTheCredentialsRealmAuthorizationIsNotChosen() {
        world.credential("alice@acme").setAuthorizedRealms(null);
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(DI, null));
    }

    @Test
    void aSuspendedAssignmentDoesNotMakeTheRealmUsable() {
        world.assignments.get("alice@acme|di-shared").setStatus(UserRealmRole.STATUS_SUSPENDED);
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(DI, null));
    }

    @Test
    void aSharedRealmWhereTheIdentityReachesNoTenantIsNotChosen() {
        world.assignments.get("alice@acme|di-shared").setAuthorizedTenantIds(List.of("someone-else"));
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(DI, null));
    }

    @Test
    void theApplicationsOwnRealmWinsOverAHomeRealmThatAlsoGrantsIt() {
        // An identity granted every application in its home realm (staff, the owner) still runs a
        // shared-realm application in that application's realm, where its data lives.
        world.credential("alice@acme").setApplicationRegEx(".*");
        assertEquals("di-shared", resolve(DI, null).getDefaultRealm());

        world.credential("alice@acme").setApplicationRegEx(null);
        world.assignments.get("alice@acme|acme-D1").setAuthorizedApplications(List.of(DI));
        assertEquals("di-shared", resolve(DI, null).getDefaultRealm());
    }

    @Test
    void aHomeRealmOwnedByTheApplicationNeedsNoAssignmentToStayHome() {
        world.assignments.remove("alice@acme|acme-D1");
        PrincipalContext context = resolve(PrincipalFixture.APP, null);
        assertEquals("acme-D1", context.getDefaultRealm());
    }

    @Test
    void anInvitedAssignmentDoesNotMakeTheRealmUsable() {
        world.assignments.get("alice@acme|di-shared").setStatus(UserRealmRole.STATUS_INVITED);
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(DI, null));
    }

    @Test
    void aDelegatingServiceMustBeScopedToTheRealmTheApplicationResolvesTo() {
        PrincipalContextRequest.Builder delegated = world.identity("svc-deleg@acme")
                .applicationId(DI)
                .actingOnBehalfOf(null, "alice@acme");
        // svc-deleg's realmRegEx (acme-*|pooled) does not reach di-shared.
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED,
                () -> world.resolver().resolve(delegated.build()));

        world.credential("svc-deleg@acme").setRealmRegEx("acme-*|pooled|di-shared");
        PrincipalContextResolution resolved = world.resolver().resolve(delegated.build());
        assertEquals("di-shared", resolved.context().getDefaultRealm());
        assertEquals("alice@acme", resolved.context().getUserId());
        assertEquals("airfreight", resolved.context().getDataDomain().getTenantId());
    }

    @Test
    void anExplicitRealmIsHonouredAsBefore() {
        assertEquals("di-shared", resolve(DI, "di-shared").getDefaultRealm());
    }

    // --- harness ---

    private PrincipalContext resolve(String applicationId, String realm) {
        PrincipalContextRequest.Builder request = world.identity("alice@acme").applicationId(applicationId);
        if (realm != null) {
            request.realm(realm);
        }
        return world.resolver().resolve(request.build()).context();
    }

    private static void own(com.e2eq.framework.model.security.Realm realm, String applicationId) {
        realm.setApplicationRef(EntityReference.builder()
                .entityRefName(applicationId).entityDisplayName(applicationId).build());
    }

    private void authorize(String userId, String realm) {
        CredentialUserIdPassword credential = world.credential(userId);
        List<CredentialUserIdPassword.RealmEntry> realms = credential.getAuthorizedRealms() == null
                ? new ArrayList<>()
                : new ArrayList<>(credential.getAuthorizedRealms());
        CredentialUserIdPassword.RealmEntry entry = new CredentialUserIdPassword.RealmEntry();
        entry.setRealmRefName(realm);
        realms.add(entry);
        credential.setAuthorizedRealms(realms);
    }

    private static PrincipalResolutionException refused(PrincipalResolutionError expected,
                                                        org.junit.jupiter.api.function.Executable call) {
        PrincipalResolutionException refusal = assertThrows(PrincipalResolutionException.class, call);
        assertEquals(expected, refusal.error(), refusal.getMessage());
        return refusal;
    }
}
