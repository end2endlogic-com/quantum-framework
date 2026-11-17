package com.e2eq.framework.security.principal;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.ServiceKind;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Identity-only resolution: every typed refusal of {@code IDENTITY_ONLY_TOKENS_DESIGN.md}
 * sections 2 and 4, and the tenancy rules for dedicated and shared realms.
 */
class PrincipalContextResolverRefusalTest {

    private final PrincipalFixture world = new PrincipalFixture();

    // --- PRINCIPAL_UNKNOWN / PRINCIPAL_DISABLED ---

    @Test
    void unknownIdentityIsRefused() {
        refused(PrincipalResolutionError.PRINCIPAL_UNKNOWN, 401,
                () -> resolve(PrincipalContextRequest.identity("sub-nobody", "nobody@acme").applicationId(PrincipalFixture.APP)));
    }

    @Test
    void aUserIdOwnedByAnotherSubjectIsRefused() {
        refused(PrincipalResolutionError.PRINCIPAL_UNKNOWN, 401,
                () -> resolve(PrincipalContextRequest.identity("sub-forged", "alice@acme").applicationId(PrincipalFixture.APP)));
    }

    @Test
    void anIdentityWithNeitherSubjectNorUserIdIsRefused() {
        refused(PrincipalResolutionError.PRINCIPAL_UNKNOWN, 401,
                () -> resolve(PrincipalContextRequest.identity(null, null).applicationId(PrincipalFixture.APP)));
    }

    @Test
    void aUserIdWithoutAVerifiedSubjectIsRefused() {
        refused(PrincipalResolutionError.PRINCIPAL_UNKNOWN, 401,
                () -> resolve(PrincipalContextRequest.identity(null, "alice@acme").applicationId(PrincipalFixture.APP)));
        refused(PrincipalResolutionError.PRINCIPAL_UNKNOWN, 401,
                () -> resolve(PrincipalContextRequest.identity(" ", "alice@acme").applicationId(PrincipalFixture.APP)));
    }

    @Test
    void disabledCredentialsAreRefused() {
        world.credential("alice@acme").setActiveStatus(ActiveStatus.INACTIVE);
        refused(PrincipalResolutionError.PRINCIPAL_DISABLED, 401, () -> resolve(world.identity("alice@acme")));
        world.credential("alice@acme").setActiveStatus(ActiveStatus.DELETED);
        refused(PrincipalResolutionError.PRINCIPAL_DISABLED, 401, () -> resolve(world.identity("alice@acme")));
    }

    // --- APPLICATION_NOT_GRANTED ---

    @Test
    void anApplicationTheIdentityIsNotGrantedIsRefused() {
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, 403,
                () -> resolve(world.identity("alice@acme").applicationId("other-app")));
    }

    @Test
    void anApplicationIsRequired() {
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, 403,
                () -> resolve(world.identity("alice@acme").applicationId(" ")));
    }

    @Test
    void anApplicationGrantedOnTheAssignmentOrByTheCredentialPatternIsAdmitted() {
        world.assignments.get("alice@acme|acme-D1").setAuthorizedApplications(List.of("other-app"));
        assertEquals("other-app", resolve(world.identity("alice@acme").applicationId("other-app")).getApplicationId());

        world.assignments.get("alice@acme|acme-D1").setAuthorizedApplications(null);
        world.credential("alice@acme").setApplicationRegEx("other-.*");
        assertEquals("other-app", resolve(world.identity("alice@acme").applicationId("other-app")).getApplicationId());
    }

    @Test
    void theRealmsHomeApplicationIsCheckedForTheResolvedRealmNotTheHomeRealm() {
        world.realms.get("acme-D2").setApplicationRef(
                EntityReference.builder().entityRefName("d2-app").entityDisplayName("d2-app").build());
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, 403,
                () -> resolve(world.identity("alice@acme").realm("acme-D2")));
    }

    // --- REALM_NOT_AUTHORIZED ---

    @Test
    void anUnregisteredRealmIsRefused() {
        refused(PrincipalResolutionError.REALM_NOT_AUTHORIZED, 403,
                () -> resolve(world.identity("alice@acme").realm("acme-D9")));
    }

    @Test
    void aRealmOutsideTheCredentialsAuthorizationIsRefused() {
        world.realm("acme-D3", RealmTenancyMode.SINGLE_TENANT, "acme-three", "o", "a");
        refused(PrincipalResolutionError.REALM_NOT_AUTHORIZED, 403,
                () -> resolve(world.identity("alice@acme").realm("acme-D3")));
    }

    // --- TENANT_NOT_AUTHORIZED / TENANT_SELECTION_REQUIRED and the tenancy rules ---

    @Test
    void aDedicatedRealmIsItsOneTenant() {
        PrincipalContext context = resolve(world.identity("alice@acme").realm("acme-D2").tenantId("acme-two"));
        assertEquals("acme-two", context.getDataDomain().getTenantId());
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, 403,
                () -> resolve(world.identity("alice@acme").realm("acme-D2").tenantId("tenant-a")));
    }

    @Test
    void aHomeDomainContextNamingAnotherTenantDoesNotCarryIntoADedicatedRealm() {
        world.credential("alice@acme").getDomainContext().setTenantId("stale-tenant");
        PrincipalContext context = resolve(world.identity("alice@acme"));
        assertEquals("acme", context.getDataDomain().getTenantId());
        assertEquals("acme-org", context.getDataDomain().getOrgRefName());
    }

    @Test
    void aHomeDomainContextNamingAnotherOrganizationDoesNotCarryIntoADedicatedRealm() {
        world.credential("alice@acme").getDomainContext().setOrgRefName("stale-org");
        PrincipalContext context = resolve(world.identity("alice@acme"));
        assertEquals("acme", context.getDataDomain().getTenantId());
        assertEquals("acme-org", context.getDataDomain().getOrgRefName());
    }

    @Test
    void aTenantTheAssignmentDoesNotAuthorizeIsRefused() {
        world.member("pooled", "tenant-c", "org-c", "acct-c");
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, 403,
                () -> resolve(world.identity("alice@acme").realm("pooled").tenantId("tenant-c")));
    }

    @Test
    void aTenantNotActiveInTheRealmIsRefused() {
        world.assignments.get("alice@acme|pooled").setAuthorizedTenantIds(List.of("tenant-a", "tenant-b", "tenant-z"));
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, 403,
                () -> resolve(world.identity("alice@acme").realm("pooled").tenantId("tenant-z")));
    }

    @Test
    void aSharedRealmWithoutAnAssignmentIsRefused() {
        world.assignments.remove("alice@acme|pooled");
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, 403,
                () -> resolve(world.identity("alice@acme").realm("pooled")));
    }

    @Test
    void severalTenantsWithoutADefaultRequireSelection() {
        refused(PrincipalResolutionError.TENANT_SELECTION_REQUIRED, 400,
                () -> resolve(world.identity("alice@acme").realm("pooled")));
    }

    @Test
    void theCredentialDomainContextTenantIsTheDefault() {
        world.credential("alice@acme").getDomainContext().setTenantId("tenant-b");
        world.credential("alice@acme").getDomainContext().setDefaultRealm("pooled");
        world.credential("alice@acme").setAuthorizedRealms(null);
        PrincipalContext context = resolve(world.identity("alice@acme"));
        assertEquals("pooled", context.getDefaultRealm());
        assertEquals("tenant-b", context.getDataDomain().getTenantId());
        assertEquals("org-b", context.getDataDomain().getOrgRefName());
    }

    @Test
    void theDataDomainPolicyDefaultTenantIsTheDefault() {
        world.credential("alice@acme").setDataDomainPolicy(
                PrincipalFixture.policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "tenant-a"));
        PrincipalContext context = resolve(world.identity("alice@acme").realm("pooled"));
        assertEquals("tenant-a", context.getDataDomain().getTenantId());
    }

    @Test
    void theFirstDefaultThatIsAuthorizedAndActiveWins() {
        // The credential tenant is authorized but not an active member; the policy default is both.
        world.credential("alice@acme").getDomainContext().setTenantId("tenant-c");
        world.assignments.get("alice@acme|pooled").setAuthorizedTenantIds(List.of("tenant-a", "tenant-b", "tenant-c"));
        world.credential("alice@acme").setDataDomainPolicy(
                PrincipalFixture.policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "tenant-a"));
        PrincipalContext context = resolve(world.identity("alice@acme").realm("pooled"));
        assertEquals("tenant-a", context.getDataDomain().getTenantId());
    }

    @Test
    void anExplicitTenantSwitchWinsOverTheDefault() {
        world.credential("alice@acme").setDataDomainPolicy(
                PrincipalFixture.policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "tenant-a"));
        PrincipalContext context = resolve(world.identity("alice@acme").realm("pooled").tenantId("tenant-b"));
        assertEquals("tenant-b", context.getDataDomain().getTenantId());
    }

    @Test
    void exactlyOneReachableTenantIsUsedWithoutADefault() {
        world.assignments.get("alice@acme|pooled").setAuthorizedTenantIds(List.of("tenant-b"));
        PrincipalContext context = resolve(world.identity("alice@acme").realm("pooled"));
        assertEquals("tenant-b", context.getDataDomain().getTenantId());
    }

    @Test
    void aSharedRealmWithNoReachableTenantIsRefused() {
        world.assignments.get("alice@acme|pooled").setAuthorizedTenantIds(List.of());
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, 403,
                () -> resolve(world.identity("alice@acme").realm("pooled")));
    }

    // --- IMPERSONATION_REFUSED ---

    @Test
    void impersonationWithoutAFilterScriptIsRefused() {
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("alice@acme").impersonate(null, "admin@acme")));
    }

    @Test
    void impersonationTheScriptDeniesIsRefused() {
        world.credential("admin@acme").setImpersonateFilterScript("false");
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("admin@acme").impersonate(null, "alice@acme")));
    }

    @Test
    void impersonatingAnUnknownIdentityIsRefused() {
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("admin@acme").impersonate(null, "nobody@acme")));
    }

    @Test
    void impersonatingAnIdentityOutsideTheListedOnesIsRefused() {
        CredentialUserIdPassword.ImpersonateEntry onlyApp = new CredentialUserIdPassword.ImpersonateEntry();
        onlyApp.setUserId("svc-app@acme");
        world.credential("admin@acme").setAuthorizedImpersonateIds(List.of(onlyApp));
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("admin@acme").impersonate(null, "alice@acme")));
    }

    @Test
    void impersonationNamingBothSubjectAndUserIdIsRefused() {
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("admin@acme").impersonate("sub-alice", "alice@acme")));
    }

    @Test
    void impersonatingAnIdentityWhoseRealmIsOutsideTheImpersonatorsAuthorizationIsRefused() {
        world.realm("other-D1", RealmTenancyMode.SINGLE_TENANT, "other", "o", "a");
        world.credential("bob@other", "sub-bob", "other-D1", "other", "user");
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("admin@acme").impersonate(null, "bob@other")));
    }

    @Test
    void impersonationIsAuthorizedForTheRealmItRunsInNotTheRequestedOne() {
        // The script admits acme-D2 only; alice lives in acme-D1, so asking for acme-D2 cannot
        // authorize an impersonation that would run in acme-D1.
        world.credential("admin@acme").setImpersonateFilterScript("realm === 'acme-D2'");
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("admin@acme").impersonate(null, "alice@acme").realm("acme-D2")));
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("admin@acme").impersonate(null, "alice@acme")));
    }

    @Test
    void impersonatingADisabledIdentityIsRefused() {
        world.credential("alice@acme").setActiveStatus(ActiveStatus.INACTIVE);
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("admin@acme").impersonate(null, "alice@acme")));
    }

    @Test
    void aServiceCredentialCannotImpersonateEvenWithAScript() {
        world.credential("svc-app@acme").setImpersonateFilterScript("true");
        world.credential("svc-deleg@acme").setImpersonateFilterScript("true");
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("svc-app@acme").impersonate(null, "alice@acme")));
        refused(PrincipalResolutionError.IMPERSONATION_REFUSED, 403,
                () -> resolve(world.identity("svc-deleg@acme").impersonate(null, "alice@acme")));
    }

    @Test
    void theCallersPermissionIsCheckedBeforeAnyTargetIsLookedUp() {
        // alice has no filter script: the refusal is the same whether the target exists or not.
        PrincipalResolutionException known = assertThrows(PrincipalResolutionException.class,
                () -> resolve(world.identity("alice@acme").impersonate(null, "admin@acme")));
        PrincipalResolutionException unknown = assertThrows(PrincipalResolutionException.class,
                () -> resolve(world.identity("alice@acme").impersonate(null, "nobody@acme")));
        assertEquals(known.getMessage(), unknown.getMessage());
        // A requested realm the caller may not use is refused before the target is considered.
        world.realm("other-D1", RealmTenancyMode.SINGLE_TENANT, "other", "o", "a");
        refused(PrincipalResolutionError.REALM_NOT_AUTHORIZED, 403,
                () -> resolve(world.identity("admin@acme").impersonate(null, "nobody@acme").realm("other-D1")));
    }

    @Test
    void impersonationRefusalsShareOnePublicMessage() {
        world.realm("other-D1", RealmTenancyMode.SINGLE_TENANT, "other", "o", "a");
        world.credential("bob@other", "sub-bob", "other-D1", "other", "user");
        world.credential("dave@acme", "sub-dave", "acme-D1", "acme", "user").setActiveStatus(ActiveStatus.DELETED);
        List<Executable> refusals = List.of(
                () -> resolve(world.identity("admin@acme").impersonate(null, "nobody@acme")),
                () -> resolve(world.identity("admin@acme").impersonate(null, "dave@acme")),
                () -> resolve(world.identity("admin@acme").impersonate(null, "bob@other")),
                () -> resolve(world.identity("admin@acme").impersonate(null, "alice@acme").realm("acme-D2")));
        for (Executable call : refusals) {
            PrincipalResolutionException refusal = assertThrows(PrincipalResolutionException.class, call);
            assertEquals(PrincipalResolutionError.IMPERSONATION_REFUSED, refusal.error(), refusal.getMessage());
            assertEquals("Impersonation refused", refusal.error().publicMessage());
        }
    }

    @Test
    void publicMessagesNameNoIdentityRealmOrStore() {
        for (PrincipalResolutionError error : PrincipalResolutionError.values()) {
            String message = error.publicMessage();
            assertFalse(message == null || message.isBlank(), error.name());
            for (String detail : List.of("acme", "pooled", "system", "@", "'")) {
                assertFalse(message.contains(detail), error + " leaks " + detail);
            }
        }
    }

    // --- DELEGATION_NOT_ALLOWED ---

    @Test
    void aUserCannotActOnBehalfOfAnyone() {
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("admin@acme").actingOnBehalfOf(null, "alice@acme")));
    }

    @Test
    void anApplicationServiceCannotActOnBehalfOfAnyone() {
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-app@acme").actingOnBehalfOf(null, "alice@acme")));
    }

    @Test
    void aServiceWithoutAKindCannotActOnBehalfOfAnyone() {
        world.credential("svc-deleg@acme").setServiceKind(null);
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme")));
    }

    @Test
    void aDelegatingServiceWithoutDelegationScopeIsRefused() {
        world.credential("svc-deleg@acme").setAuthorizedImpersonateIds(null);
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme")));
    }

    @Test
    void anIdentityOutsideTheListedScopeIsRefused() {
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "admin@acme")));
    }

    @Test
    void aScopeScriptThatDeniesIsRefused() {
        world.credential("svc-deleg@acme").setImpersonateFilterScript("realm === 'acme-D2'");
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme")));
        PrincipalContext inD2 = resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme")
                .realm("acme-D2"));
        assertEquals("acme-D2", inD2.getDefaultRealm());
    }

    @Test
    void aRealmOutsideTheServicesRealmBoundaryIsRefused() {
        world.credential("svc-deleg@acme").setRealmRegEx("acme-D1");
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme").realm("acme-D2")));
    }

    @Test
    void aServiceWithoutRealmAuthorizationDelegatesInItsHomeRealmOnly() {
        world.credential("svc-deleg@acme").setRealmRegEx(null);
        world.credential("svc-deleg@acme").setAuthorizedRealms(null);
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme").realm("acme-D2")));
        assertEquals("acme-D1", resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme"))
                .getDefaultRealm());
    }

    @Test
    void delegationAndImpersonationTogetherAreRefused() {
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme")
                        .impersonate(null, "alice@acme")));
    }

    @Test
    void anUnknownActedForIdentityIsRefused() {
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "nobody@acme")));
    }

    @Test
    void aDisabledActedForIdentityIsRefused() {
        world.credential("alice@acme").setActiveStatus(ActiveStatus.INACTIVE);
        refused(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme")));
    }

    @Test
    void unknownDisabledAndOutOfScopeIdentitiesLookTheSame() {
        world.credential("dave@acme", "sub-dave", "acme-D1", "acme", "user").setActiveStatus(ActiveStatus.DELETED);
        List<Executable> refusals = List.of(
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "nobody@acme")),
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "dave@acme")),
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "admin@acme")));
        for (Executable call : refusals) {
            PrincipalResolutionException refusal = assertThrows(PrincipalResolutionException.class, call);
            assertEquals(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, refusal.error(), refusal.getMessage());
            assertEquals("Delegation not allowed", refusal.error().publicMessage());
        }
    }

    @Test
    void theServicesRealmIsCheckedBeforeAnyActedForIdentityIsLookedUp() {
        world.credential("svc-deleg@acme").setRealmRegEx("acme-D1");
        PrincipalResolutionException known = assertThrows(PrincipalResolutionException.class,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme").realm("acme-D2")));
        PrincipalResolutionException unknown = assertThrows(PrincipalResolutionException.class,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "nobody@acme").realm("acme-D2")));
        assertEquals(PrincipalResolutionError.DELEGATION_NOT_ALLOWED, known.error());
        assertEquals(known.getMessage(), unknown.getMessage(), "the refusal does not depend on the acted-for identity");
    }

    @Test
    void theActedForIdentityIsFoundBySubjectToo() {
        PrincipalContextResolution delegated = world.resolver().resolve(
                world.identity("svc-deleg@acme").actingOnBehalfOf("sub-alice", null).build());
        assertEquals("alice@acme", delegated.context().getUserId());
    }

    @Test
    void theActedForIdentityStillNeedsTheApplication() {
        world.assignments.get("alice@acme|acme-D1").setStatus(UserRealmRole.STATUS_ACTIVE);
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, 403,
                () -> resolve(world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme")
                        .applicationId("other-app")));
    }

    @Test
    void aDelegatingServiceWithoutTheHeaderRunsAsItself() {
        PrincipalContextResolution itself = world.resolver().resolve(world.identity("svc-deleg@acme").build());
        assertEquals("svc-deleg@acme", itself.context().getUserId());
        assertEquals("SERVICE", itself.context().getScope());
        assertEquals(null, itself.actingService());
        assertEquals(ServiceKind.DELEGATING, itself.callerCredential().getServiceKind());
    }

    // --- harness ---

    private PrincipalContext resolve(PrincipalContextRequest.Builder request) {
        return world.resolver().resolve(request.build()).context();
    }

    private static void refused(PrincipalResolutionError expected, int status, Executable call) {
        PrincipalResolutionException refusal = assertThrows(PrincipalResolutionException.class, call);
        assertEquals(expected, refusal.error(), refusal.getMessage());
        assertEquals(expected.name(), refusal.code());
        assertEquals(status, refusal.httpStatus());
    }
}
