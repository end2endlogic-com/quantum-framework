package com.e2eq.framework.security.principal;

import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity-only resolution with application bindings ({@link UserAppRole}): the binding decides
 * admission, tenants and the app's roles; the realm assignment's application grant decides only
 * when no binding exists; realm-wide roles flow into every application; a malformed or suspended
 * binding refuses and never falls back.
 *
 * <p>World ({@link PrincipalFixture}): alice is a member of the shared realm {@code pooled}
 * (tenants tenant-a and tenant-b, realm role pool-user) and of the dedicated realm
 * {@code acme-D2} (tenant acme-two, realm role d2-user). {@code planner} owns no realm.
 */
class PrincipalContextApplicationBindingTest {

    private static final String PLANNER = "planner";
    private static final String REPORTS = "reports";
    private static final String ALICE = "alice@acme";

    private final PrincipalFixture world = new PrincipalFixture();

    @BeforeEach
    void plannerInstalledInThePooledRealm() {
        world.declare(PLANNER, "planner-admin", "planner-user");
        world.declare(REPORTS, "report-viewer");
        world.install(PLANNER, "pooled", "tenant-a");
        world.install(PLANNER, "pooled", "tenant-b");
        world.install(REPORTS, "pooled", "tenant-a");
        world.install(PLANNER, "acme-D2", "acme-two");
    }

    // --- admission, tenant and roles -------------------------------------------------------

    @Test
    void aBindingAdmitsTheApplicationAndAddsItsRolesForTheDefaultTenant() {
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a", "tenant-b"), "tenant-b", "planner-user");

        PrincipalContext context = resolve(PLANNER, "pooled", null);

        assertEquals("pooled", context.getDefaultRealm());
        assertEquals("tenant-b", context.getDataDomain().getTenantId(), "the binding's default tenant");
        assertEquals("org-b", context.getDataDomain().getOrgRefName());
        assertTrue(roles(context).contains("planner-user"));
        assertFalse(roles(context).contains("planner-admin"));
    }

    @Test
    void withoutABindingTheRealmAssignmentsApplicationGrantStillDecides() {
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "pooled", "tenant-a"));

        world.assignments.get(ALICE + "|pooled").setAuthorizedApplications(List.of(PLANNER));
        PrincipalContext context = resolve(PLANNER, "pooled", "tenant-a");
        assertEquals("tenant-a", context.getDataDomain().getTenantId());
        assertFalse(roles(context).contains("planner-user"), "no binding, no application roles");
    }

    @Test
    void aBindingTakesPrecedenceOverTheRealmAssignment() {
        // The assignment grants planner in every tenant it authorizes; the binding grants tenant-a only.
        world.assignments.get(ALICE + "|pooled").setAuthorizedApplications(List.of(PLANNER));
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a"), null, "planner-admin");

        PrincipalContext context = resolve(PLANNER, "pooled", null);
        assertEquals("tenant-a", context.getDataDomain().getTenantId(), "the binding's only tenant, no selection needed");
        assertTrue(roles(context).contains("planner-admin"));

        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, () -> resolve(PLANNER, "pooled", "tenant-b"));
    }

    @Test
    void realmWideRolesFlowIntoEveryApplication() {
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a"), null, "planner-user");
        world.bind(ALICE, REPORTS, "pooled", List.of("tenant-a"), null, "report-viewer");

        List<String> planner = roles(resolve(PLANNER, "pooled", null));
        List<String> reports = roles(resolve(REPORTS, "pooled", null));

        assertTrue(planner.containsAll(List.of("pool-user", "user", "planner-user")), planner.toString());
        assertTrue(reports.containsAll(List.of("pool-user", "user", "report-viewer")), reports.toString());
        assertFalse(planner.contains("report-viewer"), "one application's roles stay in that application");
        assertFalse(reports.contains("planner-user"), "one application's roles stay in that application");
    }

    // --- groups ----------------------------------------------------------------------------

    @Test
    void aGroupBindingGrantsItsRolesInItsTenant() {
        world.groupsOfUser.put(ALICE, List.of("planners"));
        world.bindGroup("planners", PLANNER, "pooled", "tenant-a", "planner-admin");

        PrincipalContext context = resolve(PLANNER, "pooled", null);
        assertEquals("tenant-a", context.getDataDomain().getTenantId());
        assertTrue(roles(context).contains("planner-admin"));
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, () -> resolve(PLANNER, "pooled", "tenant-b"));
    }

    @Test
    void groupAndUserBindingRolesApplyOnlyInTheTenantsThatGrantThem() {
        world.groupsOfUser.put(ALICE, List.of("planners"));
        world.bindGroup("planners", PLANNER, "pooled", "tenant-a", "planner-admin");
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-b"), null, "planner-user");

        PrincipalContext inA = resolve(PLANNER, "pooled", "tenant-a");
        PrincipalContext inB = resolve(PLANNER, "pooled", "tenant-b");
        assertTrue(roles(inA).contains("planner-admin") && !roles(inA).contains("planner-user"), roles(inA).toString());
        assertTrue(roles(inB).contains("planner-user") && !roles(inB).contains("planner-admin"), roles(inB).toString());
        refused(PrincipalResolutionError.TENANT_SELECTION_REQUIRED, () -> resolve(PLANNER, "pooled", null));
    }

    @Test
    void aGroupTheUserIsNotInGrantsNothing() {
        world.bindGroup("planners", PLANNER, "pooled", "tenant-a", "planner-admin");
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "pooled", "tenant-a"));
    }

    // --- realms ----------------------------------------------------------------------------

    @Test
    void aBindingInOneRealmGrantsNothingInAnother() {
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a"), null, "planner-admin");

        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "acme-D2", null));

        world.bind(ALICE, PLANNER, "acme-D2", List.of("acme-two"), null, "planner-user");
        PrincipalContext dedicated = resolve(PLANNER, "acme-D2", null);
        assertEquals("acme-two", dedicated.getDataDomain().getTenantId());
        assertTrue(roles(dedicated).contains("planner-user") && roles(dedicated).contains("d2-user"));
        assertFalse(roles(dedicated).contains("planner-admin"), "the pooled realm's binding stays there");
        assertFalse(roles(dedicated).contains("pool-user"), "the pooled realm's roles stay there");
    }

    @Test
    void aBindingForAnotherUserGrantsNothing() {
        world.bind("admin@acme", PLANNER, "pooled", List.of("tenant-a"), null, "planner-admin");
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "pooled", "tenant-a"));
    }

    @Test
    void theBindingsRealmIsUsedWhenTheRequestNamesNone() {
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a"), null, "planner-user");
        assertEquals("pooled", resolve(PLANNER, null, null).getDefaultRealm());

        UserAppRole dedicated = world.bind(ALICE, PLANNER, "acme-D2", List.of("acme-two"), null, "planner-user");
        refused(PrincipalResolutionError.REALM_NOT_AUTHORIZED, () -> resolve(PLANNER, null, null));

        dedicated.setDefaultRealm(true);
        assertEquals("acme-D2", resolve(PLANNER, null, null).getDefaultRealm());
    }

    @Test
    void aDedicatedRealmBindingMustGrantTheRealmsTenant() {
        world.install(PLANNER, "acme-D2", "someone-else");
        world.bind(ALICE, PLANNER, "acme-D2", List.of("someone-else"), null, "planner-user");
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, () -> resolve(PLANNER, "acme-D2", null));
    }

    @Test
    void aSharedRealmBindingNeedsAnActiveMembership() {
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a"), null, "planner-user");
        world.assignments.remove(ALICE + "|pooled");
        world.credential(ALICE).setAuthorizedRealms(List.of());
        world.credential(ALICE).setRealmRegEx("pooled");
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, () -> resolve(PLANNER, "pooled", "tenant-a"));
    }

    @Test
    void theMembershipBoundsTheBindingsTenants() {
        world.assignments.get(ALICE + "|pooled").setAuthorizedTenantIds(List.of("tenant-a"));
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a", "tenant-b"), "tenant-b", "planner-user");

        assertEquals("tenant-a", resolve(PLANNER, "pooled", null).getDataDomain().getTenantId());
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, () -> resolve(PLANNER, "pooled", "tenant-b"));
    }

    // --- fail closed -----------------------------------------------------------------------

    @Test
    void aBindingWithAnUndeclaredRoleRefusesAndDoesNotFallBack() {
        world.assignments.get(ALICE + "|pooled").setAuthorizedApplications(List.of(PLANNER));
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a"), null, "planner-superuser");

        PrincipalResolutionException refusal =
                refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "pooled", "tenant-a"));
        assertTrue(refusal.getMessage().contains("ROLE_NOT_DECLARED"), refusal.getMessage());
    }

    @Test
    void aBindingForSeveralTenantsWithoutADefaultRefuses() {
        world.assignments.get(ALICE + "|pooled").setAuthorizedApplications(List.of(PLANNER));
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a", "tenant-b"), null, "planner-user");

        PrincipalResolutionException refusal =
                refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "pooled", "tenant-a"));
        assertTrue(refusal.getMessage().contains("TENANT_DEFAULT_REQUIRED"), refusal.getMessage());
    }

    @Test
    void aBindingForAnUnregisteredApplicationRefuses() {
        world.applications.remove(PLANNER);
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a"), null);
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "pooled", "tenant-a"));
    }

    @Test
    void aSuspendedBindingRefusesAndDoesNotFallBack() {
        world.assignments.get(ALICE + "|pooled").setAuthorizedApplications(List.of(PLANNER));
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a"), null, "planner-user")
                .setStatus(UserAppRole.STATUS_SUSPENDED);
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "pooled", "tenant-a"));
    }

    @Test
    void duplicateUserBindingsRefuse() {
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a"), null, "planner-user");
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-b"), null, "planner-admin");
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "pooled", "tenant-a"));
    }

    @Test
    void uninstalledTenantsDoNotCount() {
        world.installs.remove(PLANNER + "|pooled|tenant-b");
        world.bind(ALICE, PLANNER, "pooled", List.of("tenant-a", "tenant-b"), "tenant-b", "planner-user");
        assertEquals("tenant-a", resolve(PLANNER, "pooled", null).getDataDomain().getTenantId());
        refused(PrincipalResolutionError.TENANT_NOT_AUTHORIZED, () -> resolve(PLANNER, "pooled", "tenant-b"));

        world.installs.remove(PLANNER + "|pooled|tenant-a");
        refused(PrincipalResolutionError.APPLICATION_NOT_GRANTED, () -> resolve(PLANNER, "pooled", null));
    }

    // --- harness ---------------------------------------------------------------------------

    private PrincipalContext resolve(String applicationId, String realm, String tenant) {
        PrincipalContextRequest.Builder request = world.identity(ALICE).applicationId(applicationId);
        if (realm != null) {
            request.realm(realm);
        }
        if (tenant != null) {
            request.tenantId(tenant);
        }
        return world.resolver().resolve(request.build()).context();
    }

    private static List<String> roles(PrincipalContext context) {
        return List.of(context.getRoles());
    }

    private static PrincipalResolutionException refused(PrincipalResolutionError expected,
                                                        org.junit.jupiter.api.function.Executable call) {
        PrincipalResolutionException refusal = assertThrows(PrincipalResolutionException.class, call);
        assertEquals(expected, refusal.error(), refusal.getMessage());
        return refusal;
    }
}
