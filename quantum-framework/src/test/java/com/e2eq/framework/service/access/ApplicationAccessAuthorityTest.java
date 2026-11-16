package com.e2eq.framework.service.access;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.ApplicationInstallRepo;
import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.UserAppRoleRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.ApplicationInstall;
import com.e2eq.framework.model.security.ApplicationRole;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.DeleteOptions;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may write application bindings and installs, against real Mongo and the framework's
 * administrator definitions: the platform, a realm administrator (in a pooled realm only for the
 * tenants its assignment authorizes) and a membership administrator of its token's tenant.
 */
@QuarkusTest
@TestProfile(ApplicationAccessAuthorityTest.MembershipAdminProfile.class)
class ApplicationAccessAuthorityTest extends BaseRepoTest {

    public static class MembershipAdminProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quantum.access-invites.membership-admin-roles", MEMBERSHIP_ADMIN);
        }
    }

    static final String MEMBERSHIP_ADMIN = "membership-admin";
    private static final String PASSWORD = "B1nd-Authority-P@ssw0rd!";
    static final String POOLED = "bind-authority-pooled";
    private static final String OTHER_REALM = "bind-authority-other";
    static final String TENANT_A = "tenant-a.bind-authority";
    private static final String TENANT_B = "tenant-b.bind-authority";

    @Inject
    ApplicationAccessService accessService;

    @Inject
    ApplicationRepo applicationRepo;

    @Inject
    ApplicationInstallRepo installRepo;

    @Inject
    UserAppRoleRepo bindingRepo;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    UserGroupRepo userGroupRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    SystemDirectory systemDirectory;

    @Inject
    RealmMembershipService realmMembershipService;

    @Inject
    SecurityUtils securityUtils;

    private String suffix;
    private String app;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> groupRefNames = new ArrayList<>();
    private final List<String[]> grantedPolicies = new ArrayList<>();

    @Inject
    com.e2eq.framework.model.persistent.morphia.PolicyRepo policyRepo;

    @Inject
    com.e2eq.framework.security.runtime.RuleContext ruleContext;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        app = "bind-authority-" + suffix;
        registerPooledRealm();
        asSystem(() -> {
            Application application = applicationRepo.ensureRegistered(app, securityUtils.getSystemDataDomain());
            application.setRoles(new ArrayList<>(List.of(
                ApplicationRole.builder().name("planner-user").grantableBy(List.of(ApplicationRole.GRANTABLE_BY_TENANT_ADMIN)).build(),
                ApplicationRole.builder().name("planner-admin").grantableBy(List.of(ApplicationRole.GRANTABLE_BY_REALM_ADMIN)).build(),
                ApplicationRole.builder().name("planner-owner").grantableBy(List.of()).build())));
            return applicationRepo.save(application);
        });
    }

    @AfterEach
    void tearDown() {
        var system = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm());
        system.find(UserAppRole.class).filter(Filters.eq("applicationId", app)).delete(new DeleteOptions().multi(true));
        system.find(ApplicationInstall.class).filter(Filters.eq("applicationId", app)).delete(new DeleteOptions().multi(true));
        system.find(Application.class).filter(Filters.eq("refName", app)).delete(new DeleteOptions().multi(true));
        for (String userId : userIds) {
            system.find(CredentialUserIdPassword.class).filter(Filters.eq("userId", userId)).delete(new DeleteOptions().multi(true));
            system.find(UserRealmRole.class).filter(Filters.eq("userId", userId)).delete(new DeleteOptions().multi(true));
        }
        system.find(UserProfile.class).filter(Filters.in("userId", userIds)).delete(new DeleteOptions().multi(true));
        morphiaDataStoreWrapper.getDataStore(POOLED).find(UserProfile.class)
            .filter(Filters.in("userId", userIds)).delete(new DeleteOptions().multi(true));
        system.find(UserGroup.class).filter(Filters.in("refName", groupRefNames)).delete(new DeleteOptions().multi(true));
        groupRefNames.clear();
        userIds.clear();
        for (String[] granted : grantedPolicies) {
            morphiaDataStoreWrapper.getDataStore(granted[0]).find(Policy.class)
                .filter(Filters.eq("refName", granted[1])).delete(new DeleteOptions().multi(true));
            ruleContext.reloadFromRepo(granted[0]);
        }
        grantedPolicies.clear();
    }

    @Inject
    AccessInviteService inviteService;

    @Test
    void permissionInspectionRequiresAuthorityOverEveryTargetTenant() {
        String admin = member("inspection-admin", List.of(TENANT_A), List.of("admin"));
        String own = member("inspection-own", List.of(TENANT_A), List.of("viewer"));
        String foreign = member("inspection-foreign", List.of(TENANT_B), List.of("viewer"));
        String shared = member("inspection-shared", List.of(TENANT_A, TENANT_B), List.of("viewer"));
        String membershipAdmin = member("inspection-membership-admin", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        assertTrue(as(signedIn(admin, TENANT_A), () -> inviteService.mayInspectPermissions(POOLED, own, null)));
        for (String target : List.of(foreign, shared, "missing-inspection-user")) {
            org.junit.jupiter.api.Assertions.assertFalse(as(signedIn(admin, TENANT_A),
                () -> inviteService.mayInspectPermissions(POOLED, target, TENANT_A)));
        }
        org.junit.jupiter.api.Assertions.assertFalse(as(signedIn(admin, TENANT_A),
            () -> inviteService.mayInspectPermissions(POOLED, own, TENANT_B)));
        org.junit.jupiter.api.Assertions.assertFalse(as(signedIn(membershipAdmin, TENANT_A),
            () -> inviteService.mayInspectPermissions(POOLED, own, TENANT_A)));
        assertTrue(asSystem(() -> inviteService.mayInspectPermissions(POOLED, foreign, TENANT_B)));
    }

    // ---- installs by id -------------------------------------------------------------------

    @Test
    void anInstallIdOfAnotherRealmIsNotReachable() {
        String admin = member("install-admin", List.of(TENANT_A), List.of("admin"));
        ApplicationInstall foreign = asSystem(() -> accessService.install(OTHER_REALM, install(null, OTHER_REALM, TENANT_A)));

        refused(Code.REALM_MISMATCH, () -> as(signedIn(admin, TENANT_A),
            () -> accessService.install(POOLED, install(foreign.getId(), null, TENANT_A))));
        ApplicationAccessException foreignId = refused(Code.NOT_FOUND,
            () -> as(signedIn(admin, TENANT_A), () -> accessService.uninstall(POOLED, foreign.getId())));
        ObjectId missing = new ObjectId();
        ApplicationAccessException missingId = refused(Code.NOT_FOUND,
            () -> as(signedIn(admin, TENANT_A), () -> accessService.uninstall(POOLED, missing)));
        assertEquals(foreignId.getMessage().replace(foreign.getId().toString(), "<id>"),
            missingId.getMessage().replace(missing.toString(), "<id>"), "a foreign id reads like a missing one");
        assertEquals(ApplicationInstall.STATUS_ACTIVE,
            installRepo.findByIdWithIgnoreRules(foreign.getId()).orElseThrow().getStatus());
    }

    @Test
    void anInstallIdKeepsItsTenantAndNeedsAuthorityOverTheStoredTenant() {
        String adminA = member("install-admin-a", List.of(TENANT_A), List.of("admin"));
        ApplicationInstall ofB = asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_B)));
        ApplicationInstall ofA = asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));

        // The pooled-realm administrator of tenant A cannot rewrite tenant B's install as A's.
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(signedIn(adminA, TENANT_A),
            () -> accessService.install(POOLED, install(ofB.getId(), null, TENANT_A))));
        // Nor move its own install to another tenant, application or realm.
        refused(Code.INSTALL_INVALID, () -> asSystem(
            () -> accessService.install(POOLED, install(ofA.getId(), null, TENANT_B))));
        refused(Code.INSTALL_INVALID, () -> asSystem(
            () -> accessService.install(OTHER_REALM, install(ofA.getId(), OTHER_REALM, TENANT_A))));
        // Re-installing under the same id is allowed and changes nothing.
        assertEquals(ofA.getId(), as(signedIn(adminA, TENANT_A),
            () -> accessService.install(POOLED, install(ofA.getId(), null, TENANT_A))).getId());
        assertEquals(TENANT_B, installRepo.findByIdWithIgnoreRules(ofB.getId()).orElseThrow().getTenantId());
    }

    // ---- grantableBy and administrator reach ---------------------------------------------

    @Test
    void aTenantAdministratorGrantsTenantAdminRolesInItsOwnTenantOnly() {
        String adminA = member("ta-admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        String alice = member("ta-alice", List.of(TENANT_A, TENANT_B), List.of("viewer"));
        String bob = member("ta-bob", List.of(TENANT_A), List.of("viewer"));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_B)));
        PrincipalContext asAdminA = signedIn(adminA, TENANT_A);

        assertEquals(List.of("planner-user"), as(asAdminA, () -> accessService.putBinding(POOLED,
            binding(alice, List.of(TENANT_A), null, "planner-user"))).getRoles());
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(asAdminA, () -> accessService.putBinding(POOLED,
            binding(bob, List.of(TENANT_A), null, "planner-admin"))));
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(asAdminA, () -> accessService.putBinding(POOLED,
            binding(bob, List.of(TENANT_A), null, "planner-owner"))));
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(asAdminA, () -> accessService.putBinding(POOLED,
            binding(alice, List.of(TENANT_A, TENANT_B), TENANT_A, "planner-user"))));
        // Signed in for tenant B, the same administrator administers nothing.
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(signedIn(adminA, TENANT_B), () -> accessService.putBinding(POOLED,
            binding(bob, List.of(TENANT_A), null, "planner-user"))));
        // Installs need a realm administrator.
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(asAdminA, () -> accessService.install(POOLED, install(null, null, TENANT_A))));
    }

    @Test
    void aPooledRealmAdministratorReachesOnlyTheTenantsItsAssignmentAuthorizes() {
        String realmAdminA = member("ra-admin-a", List.of(TENANT_A), List.of("admin"));
        String alice = member("ra-alice", List.of(TENANT_A, TENANT_B), List.of("viewer"));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_B)));
        PrincipalContext asAdmin = signedIn(realmAdminA, TENANT_A);

        UserAppRole saved = as(asAdmin, () -> accessService.putBinding(POOLED,
            binding(alice, List.of(TENANT_A), null, "planner-user", "planner-admin")));
        assertEquals(List.of("planner-user", "planner-admin"), saved.getRoles(), "realm-admin roles are grantable");
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(asAdmin, () -> accessService.putBinding(POOLED,
            binding(alice, List.of(TENANT_A), null, "planner-owner"))));
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(asAdmin, () -> accessService.putBinding(POOLED,
            binding(alice, List.of(TENANT_A, TENANT_B), TENANT_A, "planner-user"))));
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(asAdmin, () -> accessService.install(POOLED, install(null, null, TENANT_B))));

        // Replacing a binding needs authority over the tenants it already grants too.
        asSystem(() -> accessService.putBinding(POOLED, binding(alice, List.of(TENANT_A, TENANT_B), TENANT_A, "planner-user")));
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(asAdmin, () -> accessService.putBinding(POOLED,
            binding(alice, List.of(TENANT_A), null, "planner-user"))));

        // The platform grants a platform-only role.
        assertEquals(List.of("planner-owner"), asSystem(() -> accessService.putBinding(POOLED,
            binding(alice, List.of(TENANT_A, TENANT_B), TENANT_A, "planner-owner"))).getRoles());
    }

    // ---- group bindings -------------------------------------------------------------------

    @Test
    void aGroupBindingGrantsExactlyTheGroupsOwnerTenant() {
        String adminA = member("group-admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        String adminB = member("group-admin-b", List.of(TENANT_B), List.of(MEMBERSHIP_ADMIN));
        String owned = group("owned", TENANT_A);
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_B)));

        refused(Code.BINDING_INVALID, () -> as(signedIn(adminA, TENANT_A),
            () -> accessService.putBinding(POOLED, groupBinding(owned, List.of(TENANT_A, TENANT_B), TENANT_A, "planner-user"))));
        refused(Code.BINDING_INVALID, () -> as(signedIn(adminB, TENANT_B),
            () -> accessService.putBinding(POOLED, groupBinding(owned, List.of(TENANT_B), null, "planner-user"))));
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(signedIn(adminB, TENANT_B),
            () -> accessService.putBinding(POOLED, groupBinding(owned, List.of(TENANT_A), null, "planner-user"))));
        refused(Code.BINDING_INVALID, () -> asSystem(
            () -> accessService.putBinding(POOLED, groupBinding(owned, List.of(TENANT_B), null, "planner-user"))));

        UserAppRole saved = as(signedIn(adminA, TENANT_A),
            () -> accessService.putBinding(POOLED, groupBinding(owned, List.of(TENANT_A), null, "planner-user")));
        assertEquals(List.of(TENANT_A), saved.getTenantIds());
    }

    @Test
    void aGroupWithoutAnOwnerTenantIsBoundByThePlatformOnly() {
        String adminA = member("ownerless-admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        String ownerless = group("ownerless", null);
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));

        refused(Code.ROLE_NOT_GRANTABLE, () -> as(signedIn(adminA, TENANT_A),
            () -> accessService.putBinding(POOLED, groupBinding(ownerless, List.of(TENANT_A), null, "planner-user"))));
        assertEquals(ownerless, asSystem(() -> accessService.putBinding(POOLED,
            groupBinding(ownerless, List.of(TENANT_A), null, "planner-user"))).getGroupRefName());
    }

    // ---- request fields the server owns ---------------------------------------------------

    @Test
    void theServerSetsTheSourceWhateverTheRequestSays() {
        String adminA = member("source-admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        String alice = member("source-alice", List.of(TENANT_A), List.of("viewer"));
        String bob = member("source-bob", List.of(TENANT_A), List.of("viewer"));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));

        UserAppRole byAdmin = binding(alice, List.of(TENANT_A), null, "planner-user");
        byAdmin.setSource(UserAppRole.SOURCE_MIGRATION);
        assertEquals(UserAppRole.SOURCE_TENANT_ADMIN,
            as(signedIn(adminA, TENANT_A), () -> accessService.putBinding(POOLED, byAdmin)).getSource());
        UserAppRole byPlatform = binding(bob, List.of(TENANT_A), null, "planner-user");
        byPlatform.setSource(UserAppRole.SOURCE_INVITE);
        assertEquals(UserAppRole.SOURCE_STAFF, asSystem(() -> accessService.putBinding(POOLED, byPlatform)).getSource());
    }

    @Test
    void onlyTheSubjectOrThePlatformMakesABindingsRealmTheDefault() {
        String adminA = member("default-admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        String alice = member("default-alice", List.of(TENANT_A), List.of("viewer"));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));

        UserAppRole forAlice = binding(alice, List.of(TENANT_A), null, "planner-user");
        forAlice.setDefaultRealm(true);
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(signedIn(adminA, TENANT_A), () -> accessService.putBinding(POOLED, forAlice)));

        UserAppRole forSelf = binding(adminA, List.of(TENANT_A), null, "planner-user");
        forSelf.setDefaultRealm(true);
        assertEquals(Boolean.TRUE, as(signedIn(adminA, TENANT_A), () -> accessService.putBinding(POOLED, forSelf)).getDefaultRealm());

        UserAppRole byPlatform = binding(alice, List.of(TENANT_A), null, "planner-user");
        byPlatform.setDefaultRealm(true);
        asSystem(() -> accessService.putBinding(POOLED, byPlatform));
        // Once set, an administrator may rewrite the binding and keep it.
        UserAppRole kept = binding(alice, List.of(TENANT_A), null);
        kept.setDefaultRealm(true);
        assertEquals(Boolean.TRUE, as(signedIn(adminA, TENANT_A), () -> accessService.putBinding(POOLED, kept)).getDefaultRealm());
    }

    @Test
    void onlyActiveMembersOfTheRealmAreBound() {
        String adminA = member("stranger-admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        String stranger = user("stranger");
        String invited = member("invited", List.of(TENANT_A), List.of("viewer"));
        writeAssignment(invited, List.of("viewer"), List.of(TENANT_A), UserRealmRole.STATUS_INVITED);
        String suspended = member("suspended", List.of(TENANT_A), List.of("viewer"));
        writeAssignment(suspended, List.of("viewer"), List.of(TENANT_A), UserRealmRole.STATUS_SUSPENDED);
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));

        for (String subject : List.of(stranger, invited, suspended)) {
            refused(Code.BINDING_SUBJECT_INVALID, () -> as(signedIn(adminA, TENANT_A),
                () -> accessService.putBinding(POOLED, binding(subject, List.of(TENANT_A), null, "planner-user"))));
            refused(Code.BINDING_SUBJECT_INVALID, () -> asSystem(
                () -> accessService.putBinding(POOLED, binding(subject, List.of(TENANT_A), null, "planner-user"))));
        }
        assertEquals(0, bindingRepo.findByApplicationWithIgnoreRules(app, POOLED, null).size());
    }

    @Test
    void aBindingIdKeepsItsSubjectAndApplication() {
        String alice = member("subject-alice", List.of(TENANT_A), List.of("viewer"));
        String mallory = member("subject-mallory", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));
        UserAppRole forAlice = binding(alice, List.of(TENANT_A), null, "planner-user");
        forAlice.setDefaultRealm(true);
        UserAppRole aliceBinding = asSystem(() -> accessService.putBinding(POOLED, forAlice));
        String owned = group("subject-owned", TENANT_A);

        // Naming alice's id, an administrator cannot move her binding (and its defaultRealm) to itself.
        UserAppRole moved = binding(mallory, List.of(TENANT_A), null, "planner-user");
        moved.setId(aliceBinding.getId());
        moved.setDefaultRealm(true);
        refused(Code.BINDING_SUBJECT_INVALID, () -> as(signedIn(mallory, TENANT_A), () -> accessService.putBinding(POOLED, moved)));
        refused(Code.BINDING_SUBJECT_INVALID, () -> asSystem(() -> accessService.putBinding(POOLED, moved)));
        // Nor turn it into a group binding.
        UserAppRole toGroup = groupBinding(owned, List.of(TENANT_A), null, "planner-user");
        toGroup.setId(aliceBinding.getId());
        refused(Code.BINDING_SUBJECT_INVALID, () -> asSystem(() -> accessService.putBinding(POOLED, toGroup)));

        UserAppRole stored = bindingRepo.findByIdWithIgnoreRules(aliceBinding.getId()).orElseThrow();
        assertEquals(alice, stored.getUserId());
        assertEquals(Boolean.TRUE, stored.getDefaultRealm());
        // The same subject under its id is an ordinary update.
        UserAppRole same = binding(alice, List.of(TENANT_A), null, "planner-user");
        same.setId(aliceBinding.getId());
        same.setDefaultRealm(true);
        assertEquals(aliceBinding.getId(), asSystem(() -> accessService.putBinding(POOLED, same)).getId());
    }

    @Test
    void privilegedRoleNamesAreNeverBound() {
        String alice = member("privileged-alice", List.of(TENANT_A), List.of("viewer"));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));
        List<String> privileged = List.of("platformAdmin", "tenantAdmin", "ANONYMOUS", MEMBERSHIP_ADMIN, "admin", "System");
        // An application row that declares them anyway, grantable by a tenant administrator.
        asSystem(() -> {
            Application application = applicationRepo.findByRefNameWithIgnoreRules(app).orElseThrow();
            privileged.forEach(name -> application.getRoles().add(
                ApplicationRole.builder().name(name).grantableBy(List.of(ApplicationRole.GRANTABLE_BY_TENANT_ADMIN)).build()));
            return applicationRepo.save(application);
        });
        for (String role : privileged) {
            refused(Code.ROLE_NOT_GRANTABLE, () -> asSystem(() -> accessService.putBinding(POOLED,
                binding(alice, List.of(TENANT_A), null, role))));
            UserAppRole direct = binding(alice, List.of(TENANT_A), null, role);
            direct.setRealmRefName(POOLED);
            refused(Code.ROLE_NOT_GRANTABLE, () -> asSystem(() -> bindingRepo.save(direct)));
        }
        assertEquals(0, bindingRepo.findByApplicationWithIgnoreRules(app, POOLED, null).size());
    }

    @Test
    void aBindingIdOfAnotherRealmReadsLikeAMissingOne() {
        String adminA = member("foreign-binding-admin", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        String alice = member("foreign-binding-alice", List.of(TENANT_A), List.of("viewer"));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));
        UserAppRole pooled = asSystem(() -> accessService.putBinding(POOLED, binding(alice, List.of(TENANT_A), null, "planner-user")));

        UserAppRole rewrite = binding(alice, List.of(TENANT_A), null, "planner-user");
        rewrite.setId(pooled.getId());
        rewrite.setRealmRefName(OTHER_REALM);
        refused(Code.NOT_FOUND, () -> asSystem(() -> accessService.putBinding(OTHER_REALM, rewrite)));
        ApplicationAccessException foreign = refused(Code.NOT_FOUND, () -> as(signedIn(adminA, TENANT_A), () -> {
            accessService.deleteBinding(OTHER_REALM, pooled.getId());
            return null;
        }));
        ObjectId missing = new ObjectId();
        ApplicationAccessException absent = refused(Code.NOT_FOUND, () -> as(signedIn(adminA, TENANT_A), () -> {
            accessService.deleteBinding(OTHER_REALM, missing);
            return null;
        }));
        assertEquals(foreign.getMessage().replace(pooled.getId().toString(), "<id>"),
            absent.getMessage().replace(missing.toString(), "<id>"), "a foreign id reads like a missing one");
        assertTrue(bindingRepo.findByIdWithIgnoreRules(pooled.getId()).isPresent());
    }

    @Test
    void anUninstallsSuspensionSurvivesARewriteAndTheReinstallReactivatesIt() {
        String adminA = member("marker-admin-a", List.of(TENANT_A), List.of(MEMBERSHIP_ADMIN));
        String owned = group("marker-owned", TENANT_A);
        ApplicationInstall installed = asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));
        UserAppRole saved = as(signedIn(adminA, TENANT_A),
            () -> accessService.putBinding(POOLED, groupBinding(owned, List.of(TENANT_A), null, "planner-user")));
        asSystem(() -> accessService.uninstall(POOLED, installed.getId()));
        String marker = UserAppRole.SUSPENDED_BY_UNINSTALL + installed.getId();
        assertEquals(marker, bindingRepo.findByIdWithIgnoreRules(saved.getId()).orElseThrow().getSuspendedBy());

        // While uninstalled the suspended binding stays editable, and keeps the uninstall's marker
        // whether the rewrite names the status or leaves it out.
        UserAppRole named = groupBinding(owned, List.of(TENANT_A), null, "planner-user", "planner-admin");
        named.setStatus(UserAppRole.STATUS_SUSPENDED);
        UserAppRole rewritten = asSystem(() -> accessService.putBinding(POOLED, named));
        assertEquals(UserAppRole.STATUS_SUSPENDED, rewritten.getStatus());
        assertEquals(marker, rewritten.getSuspendedBy());
        UserAppRole unnamed = groupBinding(owned, List.of(TENANT_A), null, "planner-user");
        assertEquals(marker, asSystem(() -> accessService.putBinding(POOLED, unnamed)).getSuspendedBy());
        // Activating it while the application is uninstalled is refused.
        UserAppRole active = groupBinding(owned, List.of(TENANT_A), null, "planner-user");
        active.setStatus(UserAppRole.STATUS_ACTIVE);
        refused(Code.APPLICATION_NOT_INSTALLED, () -> asSystem(() -> accessService.putBinding(POOLED, active)));

        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));
        UserAppRole after = bindingRepo.findByIdWithIgnoreRules(saved.getId()).orElseThrow();
        assertEquals(UserAppRole.STATUS_ACTIVE, after.getStatus(), "the reinstall reactivates the rewritten binding");
        assertEquals(java.util.List.of("planner-user"), after.getRoles());
    }

    // ---- listing ----------------------------------------------------------------------------

    @Test
    void anAdministratorSeesOnlyTheTenantsItAdministers() {
        String realmAdminA = member("list-admin-a", List.of(TENANT_A), List.of("admin"));
        String adminB = member("list-admin-b", List.of(TENANT_B), List.of(MEMBERSHIP_ADMIN));
        String alice = member("list-alice", List.of(TENANT_A, TENANT_B), List.of("viewer"));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A)));
        asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_B)));
        asSystem(() -> accessService.putBinding(POOLED, binding(alice, List.of(TENANT_A, TENANT_B), TENANT_B, "planner-user")));

        List<UserAppRole> asAdminA = as(signedIn(realmAdminA, TENANT_A), () -> accessService.listBindings(POOLED, app, TENANT_A));
        assertEquals(1, asAdminA.size());
        assertEquals(List.of(TENANT_A), asAdminA.get(0).getTenantIds(), "tenant B is not shown to A's administrator");
        assertEquals(null, asAdminA.get(0).getDefaultTenantId(), "nor is a default outside A");
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(signedIn(realmAdminA, TENANT_A), () -> accessService.listBindings(POOLED, app, TENANT_B)));
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(signedIn(adminB, TENANT_B), () -> accessService.listBindings(POOLED, app, TENANT_A)));

        assertEquals(List.of(TENANT_A, TENANT_B),
            asSystem(() -> accessService.listBindings(POOLED, app, TENANT_A)).get(0).getTenantIds());
        assertEquals(List.of(TENANT_A, TENANT_B), bindingRepo.findByApplicationWithIgnoreRules(app, POOLED, null)
            .get(0).getTenantIds(), "the stored row is unchanged");
    }

    @Test
    void aPolicyGrantedServiceIdentityListsAnotherRealmsInstallsWithFilters() {
        ApplicationInstall pooledA = asSystem(() -> accessService.install(POOLED, install(null, POOLED, TENANT_A)));
        ApplicationInstall pooledB = asSystem(() -> accessService.install(POOLED, install(null, POOLED, TENANT_B)));
        ApplicationInstall other = asSystem(() -> accessService.install(OTHER_REALM, install(null, OTHER_REALM, TENANT_A)));
        asSystem(() -> accessService.uninstall(POOLED, pooledB.getId()));
        String system = envConfigUtils.getSystemRealm();
        PrincipalContext reader = service("install-reader");
        grantInstallList(reader.getUserId(), POOLED, OTHER_REALM);

        // Homed in the system realm and not the system user: the policy alone lets it name the pooled realm,
        // and it sees every install there, any status, in creation order.
        assertEquals(List.of(pooledA.getId(), pooledB.getId()), ids(as(reader, () -> accessService.listInstalls(system, POOLED, app, null))));
        assertEquals(List.of(ApplicationInstall.STATUS_ACTIVE, ApplicationInstall.STATUS_UNINSTALLED),
            as(reader, () -> accessService.listInstalls(system, POOLED, app, null)).stream().map(ApplicationInstall::getStatus).toList());
        assertEquals(List.of(pooledB.getId()), ids(as(reader, () -> accessService.listInstalls(system, POOLED, " " + app + " ", TENANT_B))));
        assertEquals(List.of(other.getId()), ids(as(reader, () -> accessService.listInstalls(system, OTHER_REALM, app, TENANT_A))));
        // In the pooled realm the system principal administers every tenant, as before.
        assertEquals(List.of(pooledA.getId(), pooledB.getId()), ids(asSystem(() -> accessService.listInstalls(POOLED, " ", app, null))));
    }

    @Test
    void withoutThePolicyNoCallerNamesAnotherRealmNotEvenTheSystemPrincipalOrASystemRoleService() {
        asSystem(() -> accessService.install(POOLED, install(null, POOLED, TENANT_A)));
        String system = envConfigUtils.getSystemRealm();
        PrincipalContext granted = service("install-reader-granted");
        grantInstallList(granted.getUserId(), POOLED);
        PrincipalContext systemRoleService = new PrincipalContext.Builder()
            .withDefaultRealm(system)
            .withDataDomain(securityUtils.getSystemDataDomain())
            .withUserId("install-reader-ungranted-" + suffix)
            .withApplicationId(app)
            .withRoles(new String[]{"system", "user"})
            .withScope(PrincipalContext.SCOPE_SERVICE)
            .build();

        refused(Code.REALM_MISMATCH, () -> as(systemRoleService, () -> accessService.listInstalls(system, POOLED, app, null)));
        PrincipalContext systemUser = new PrincipalContext.Builder()
            .withDefaultRealm(system)
            .withDataDomain(securityUtils.getSystemDataDomain())
            .withUserId(envConfigUtils.getSystemUserId())
            .withApplicationId(app)
            .withRoles(new String[]{"system"})
            .withScope("systemGenerated")
            .build();
        refused(Code.REALM_MISMATCH, () -> as(systemUser, () -> accessService.listInstalls(system, POOLED, app, null)));
        // The grant names its realms: another realm stays refused for the granted identity too.
        refused(Code.REALM_MISMATCH, () -> as(granted, () -> accessService.listInstalls(system, OTHER_REALM, app, null)));
        assertEquals(1, as(granted, () -> accessService.listInstalls(system, POOLED, app, null)).size());
    }

    @Test
    void aUsersRolesDoNotCrossIntoTheNamedRealmAServicesSignedRolesDo() {
        asSystem(() -> accessService.install(POOLED, install(null, POOLED, TENANT_A)));
        String system = envConfigUtils.getSystemRealm();
        String role = "install-reader-" + suffix;
        grant(installListPolicy(role, Policy.PrincipalType.ROLE, securityUtils.getSystemDataDomain(), null), POOLED);
        PrincipalContext user = new PrincipalContext.Builder()
            .withDefaultRealm(system)
            .withDataDomain(securityUtils.getSystemDataDomain())
            .withUserId("install-reader-user-" + suffix)
            .withApplicationId(app)
            .withRoles(new String[]{role})
            .withScope("AUTHENTICATED")
            .build();
        PrincipalContext service = new PrincipalContext.Builder()
            .withDefaultRealm(system)
            .withDataDomain(securityUtils.getSystemDataDomain())
            .withUserId("install-reader-service-" + suffix)
            .withApplicationId(app)
            .withRoles(new String[]{role})
            .withScope(PrincipalContext.SCOPE_SERVICE)
            .build();

        // A user's role may come from its assignment in the realm it is in; the named realm never gave it.
        refused(Code.REALM_MISMATCH, () -> as(user, () -> accessService.listInstalls(system, POOLED, app, null)));
        // A service identity's roles are signed into its token and hold in every realm.
        assertEquals(1, as(service, () -> accessService.listInstalls(system, POOLED, app, null)).size());
    }

    @Test
    void aGrantThatExcludesFieldsDoesNotReadTheRealmWhole() {
        asSystem(() -> accessService.install(POOLED, install(null, POOLED, TENANT_A)));
        String system = envConfigUtils.getSystemRealm();
        PrincipalContext reader = service("install-reader-excluded");
        grant(installListPolicy(reader.getUserId(), Policy.PrincipalType.USER, securityUtils.getSystemDataDomain(),
            List.of("installedBy")), POOLED);

        // The read returns whole rows, so a grant narrowed by field is not realm-wide: refused, never trimmed silently.
        refused(Code.REALM_MISMATCH, () -> as(reader, () -> accessService.listInstalls(system, POOLED, app, null)));
    }

    @Test
    void aNonPlatformCallerNamingAnotherRealmIsRefusedNeverAnsweredForItsOwn() {
        String realmAdminA = member("list-cross-admin", List.of(TENANT_A), List.of("admin"));
        asSystem(() -> accessService.install(POOLED, install(null, POOLED, TENANT_A)));
        asSystem(() -> accessService.install(OTHER_REALM, install(null, OTHER_REALM, TENANT_A)));

        refused(Code.REALM_MISMATCH, () -> as(signedIn(realmAdminA, TENANT_A),
            () -> accessService.listInstalls(POOLED, OTHER_REALM, app, null)));
        refused(Code.REALM_MISMATCH, () -> as(signedIn(realmAdminA, TENANT_A),
            () -> accessService.listInstalls(POOLED, envConfigUtils.getSystemRealm(), null, null)));
        // Naming its own realm is the same as naming none.
        assertEquals(ids(as(signedIn(realmAdminA, TENANT_A), () -> accessService.listInstalls(POOLED))),
            ids(as(signedIn(realmAdminA, TENANT_A), () -> accessService.listInstalls(POOLED, POOLED, null, null))));
    }

    @Test
    void aNonPlatformCallerKeepsSeeingOnlyItsAdministeredTenantsUnderFilters() {
        String realmAdminA = member("list-filter-admin", List.of(TENANT_A), List.of("admin"));
        String alice = member("list-filter-alice", List.of(TENANT_A), List.of("viewer"));
        ApplicationInstall ofA = asSystem(() -> accessService.install(POOLED, install(null, POOLED, TENANT_A)));
        asSystem(() -> accessService.install(POOLED, install(null, POOLED, TENANT_B)));

        assertEquals(List.of(ofA.getId()), ids(as(signedIn(realmAdminA, TENANT_A),
            () -> accessService.listInstalls(POOLED, null, app, null))));
        assertEquals(List.of(), ids(as(signedIn(realmAdminA, TENANT_A),
            () -> accessService.listInstalls(POOLED, null, app, TENANT_B))), "tenant B is filtered out, not revealed");
        assertEquals(List.of(), ids(as(signedIn(alice, TENANT_A),
            () -> accessService.listInstalls(POOLED, null, app, null))), "a non-administrator sees none");
    }

    @Test
    void filtersThatMatchNothingAnswerEmptyAndAnUnknownApplicationIsRefused() {
        ApplicationInstall ofApp = asSystem(() -> accessService.install(POOLED, install(null, POOLED, TENANT_A)));
        String system = envConfigUtils.getSystemRealm();
        PrincipalContext reader = service("install-reader-filters");
        String emptyRealm = "bind-authority-empty-" + suffix;
        grantInstallList(reader.getUserId(), POOLED, OTHER_REALM, emptyRealm);
        String otherApp = "bind-authority-other-app-" + suffix;
        try {
            asSystem(() -> applicationRepo.save(applicationRepo.ensureRegistered(otherApp, securityUtils.getSystemDataDomain())));
            ApplicationInstall ofOther = install(null, POOLED, TENANT_A);
            ofOther.setApplicationId(otherApp);
            ApplicationInstall otherInstalled = asSystem(() -> accessService.install(POOLED, ofOther));
            // Same realm and tenant, another application: the application filter tells them apart.
            assertEquals(List.of(ofApp.getId()), ids(as(reader, () -> accessService.listInstalls(system, POOLED, app, TENANT_A))));
            assertEquals(List.of(otherInstalled.getId()), ids(as(reader, () -> accessService.listInstalls(system, POOLED, otherApp, TENANT_A))));
            assertEquals(List.of(ofApp.getId(), otherInstalled.getId()), ids(as(reader, () -> accessService.listInstalls(system, POOLED, null, TENANT_A)))
                .stream().filter(id -> id.equals(ofApp.getId()) || id.equals(otherInstalled.getId())).toList());
            // A registered application with no install in the realm answers empty.
            assertEquals(List.of(), as(reader, () -> accessService.listInstalls(system, OTHER_REALM, otherApp, null)));
        } finally {
            var ds = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm());
            ds.find(ApplicationInstall.class).filter(Filters.eq("applicationId", otherApp)).delete(new DeleteOptions().multi(true));
            ds.find(Application.class).filter(Filters.eq("refName", otherApp)).delete(new DeleteOptions().multi(true));
        }

        assertEquals(List.of(), as(reader, () -> accessService.listInstalls(system, POOLED, app, "tenant-none.bind-authority")));
        assertEquals(List.of(), as(reader, () -> accessService.listInstalls(system, emptyRealm, app, null)));
        refused(Code.APPLICATION_UNKNOWN, () -> as(reader, () -> accessService.listInstalls(system, POOLED, "unregistered-" + suffix, null)));
    }

    private static List<ObjectId> ids(List<ApplicationInstall> installs) {
        return installs.stream().map(ApplicationInstall::getId).toList();
    }

    // ---- remote directory -----------------------------------------------------------------

    @Test
    void underARemoteDirectoryBindingAndInstallCallsRefuse() {
        Object before = accessDirectoryRouting();
        setAccessDirectoryRouting((com.e2eq.framework.model.persistent.morphia.DirectoryRouting) () -> true);
        try {
            refused(Code.DIRECTORY_REMOTE, () -> asSystem(() -> accessService.install(POOLED, install(null, null, TENANT_A))));
            refused(Code.DIRECTORY_REMOTE, () -> asSystem(() -> accessService.uninstall(POOLED, new ObjectId())));
            refused(Code.DIRECTORY_REMOTE, () -> asSystem(() -> accessService.putBinding(POOLED,
                binding("someone", List.of(TENANT_A), null, "planner-user"))));
            refused(Code.DIRECTORY_REMOTE, () -> asSystem(() -> {
                accessService.deleteBinding(POOLED, new ObjectId());
                return null;
            }));
            refused(Code.DIRECTORY_REMOTE, () -> asSystem(() -> accessService.listInstalls(POOLED)));
            refused(Code.DIRECTORY_REMOTE, () -> asSystem(() -> accessService.listInstalls(POOLED, OTHER_REALM, app, TENANT_A)));
            refused(Code.DIRECTORY_REMOTE, () -> asSystem(() -> accessService.listBindings(POOLED, app, TENANT_A)));
        } finally {
            setAccessDirectoryRouting(before);
        }
        assertEquals(0, installRepo.findByRealmWithIgnoreRules(POOLED).stream()
            .filter(install -> app.equals(install.getApplicationId())).count());
    }

    // ---- fixtures -------------------------------------------------------------------------

    @Inject
    com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory accessDirectory;

    private Object accessDirectoryRouting() {
        try {
            return routingField().get(unproxied());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setAccessDirectoryRouting(Object routing) {
        try {
            routingField().set(unproxied(), routing);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object unproxied() {
        return accessDirectory instanceof io.quarkus.arc.ClientProxy proxy ? proxy.arc_contextualInstance() : accessDirectory;
    }

    private static java.lang.reflect.Field routingField() throws NoSuchFieldException {
        java.lang.reflect.Field field = com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory.class
            .getDeclaredField("routing");
        field.setAccessible(true);
        return field;
    }

    /** A system-realm group owned by {@code ownerTenant} (none when null). */
    String group(String label, String ownerTenant) {
        String refName = ("BIND_GROUP_" + label + "_" + suffix).toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        groupRefNames.add(refName);
        asSystem(() -> {
            UserGroup group = new UserGroup();
            group.setRefName(refName);
            group.setDisplayName(refName);
            group.setOwnerTenantId(ownerTenant);
            group.setDataDomain(securityUtils.getSystemDataDomain());
            return userGroupRepo.save(envConfigUtils.getSystemRealm(), group);
        });
        return refName;
    }

    UserAppRole groupBinding(String group, List<String> tenants, String defaultTenant, String... roles) {
        UserAppRole binding = binding(null, tenants, defaultTenant, roles);
        binding.setGroupRefName(group);
        return binding;
    }

    ApplicationInstall install(ObjectId id, String realm, String tenant) {
        ApplicationInstall install = new ApplicationInstall();
        install.setId(id);
        install.setApplicationId(app);
        install.setRealmRefName(realm);
        install.setTenantId(tenant);
        return install;
    }

    UserAppRole binding(String user, List<String> tenants, String defaultTenant, String... roles) {
        UserAppRole binding = new UserAppRole();
        binding.setUserId(user);
        binding.setApplicationId(app);
        binding.setTenantIds(new ArrayList<>(tenants));
        binding.setDefaultTenantId(defaultTenant);
        binding.setRoles(new ArrayList<>(List.of(roles)));
        return binding;
    }

    private void registerPooledRealm() {
        asSystem(() -> {
            Realm realm = systemDirectory.findRealmByRefName(POOLED).orElseGet(() -> systemDirectory.registerRealm(Realm.builder()
                .refName(POOLED)
                .displayName(POOLED)
                .databaseName(POOLED)
                .emailDomain(POOLED + ".bind-test.example")
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

    String user(String label) {
        String userId = label + "-" + suffix + "@bind-authority-test.example";
        userIds.add(userId);
        return userId;
    }

    /** A user homed in the first of {@code tenants}, with an active pooled-realm assignment holding {@code roles}. */
    String member(String label, List<String> tenants, List<String> roles) {
        String userId = user(label);
        asSystem(() -> authProviderFactory.getUserManager().createUser(userId, PASSWORD, false, Set.of("user"), home(tenants.get(0))));
        writeAssignment(userId, roles, tenants, UserRealmRole.STATUS_ACTIVE);
        return userId;
    }

    void writeAssignment(String userId, List<String> roles, List<String> tenants, String status) {
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

    /** The verified principal of {@code userId} in the pooled realm with a token for {@code tenant}. */
    PrincipalContext signedIn(String userId, String tenant) {
        return new PrincipalContext.Builder()
            .withDefaultRealm(POOLED)
            .withDataDomain(PooledRealmTenantInvitePolicy.copy(partition(tenant), userId))
            .withUserId(userId)
            .withScope("Authentication")
            .withRoles(new String[]{"user"})
            .build();
    }

    /** A service identity homed in the system realm that is not the system user and holds no system role. */
    PrincipalContext service(String label) {
        return new PrincipalContext.Builder()
            .withDefaultRealm(envConfigUtils.getSystemRealm())
            .withDataDomain(securityUtils.getSystemDataDomain())
            .withUserId(label + "-" + suffix)
            .withApplicationId(app)
            .withRoles(new String[]{"user"})
            .withScope(PrincipalContext.SCOPE_SERVICE)
            .build();
    }

    /**
     * The grant an identity pack carries: a USER policy for {@code userId} allowing
     * {@code SECURITY/APPLICATION_INSTALL/LIST} in every realm ({@code realmRefName} and rule body
     * realm {@code *}). Policy is read from each operating realm here (no policy-store realm is
     * configured in this suite), so it is stored in each realm the test names and removed after.
     */
    void grantInstallList(String userId, String... realms) {
        for (String realm : realms) {
            grant(installListPolicy(userId, securityUtils.getSystemDataDomain()), realm);
        }
    }

    void grant(Policy policy, String... realms) {
        for (String realm : realms) {
            try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
                policyRepo.save(realm, policy);
                ruleContext.reloadFromRepo(realm);
            }
            grantedPolicies.add(new String[]{realm, policy.getRefName()});
        }
    }

    /**
     * A USER policy for {@code userId}: {@code SECURITY/APPLICATION_INSTALL/LIST} in every realm, every
     * application, ranked before the built-in tenant-admin rule (priority 0) so that holding {@code admin}
     * too does not narrow it to a tenant.
     */
    static Policy installListPolicy(String userId, DataDomain dataDomain) {
        return installListPolicy(userId, Policy.PrincipalType.USER, dataDomain, null);
    }

    static Policy installListPolicy(String principal, Policy.PrincipalType type, DataDomain dataDomain,
                                    List<String> excludedFields) {
        String userId = principal;
        Policy policy = new Policy();
        policy.setRefName(("INSTALL_LIST_GRANT_" + userId).toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "_"));
        policy.setDisplayName("install list grant for " + userId);
        policy.setPrincipalId(userId);
        policy.setPrincipalType(type);
        policy.setApplicationId("*");
        policy.setRealmRefName("*");
        policy.setDataDomain(dataDomain);
        policy.getRules().add(new Rule.Builder()
            .withName("install-list-every-realm")
            .withSecurityURI(new SecurityURI(
                new SecurityURIHeader.Builder().withIdentity(userId).withArea("SECURITY")
                    .withFunctionalDomain("APPLICATION_INSTALL").withAction("LIST").build(),
                new SecurityURIBody.Builder().withRealm("*").withOrgRefName("*").withAccountNumber("*")
                    .withTenantId("*").withOwnerId("*").withDataSegment("*").withResourceId("*").build()))
            .withEffect(RuleEffect.ALLOW)
            .withPriority(-10)
            .withFinalRule(true)
            .withExcludedFields(excludedFields)
            .build());
        return policy;
    }

    <T> T as(PrincipalContext principal, Supplier<T> action) {
        return SecurityCallScope.runWithContexts(principal, rContext, action);
    }

    <T> T asSystem(Supplier<T> action) {
        return SecurityCallScope.runWithContexts(securityUtils.getSystemPrincipalContext(),
            securityUtils.getSystemSecurityResourceContext(), action);
    }

    static ApplicationAccessException refused(Code expected, Executable action) {
        ApplicationAccessException refusal = assertThrows(ApplicationAccessException.class, action);
        assertEquals(expected, refusal.code(), refusal.getMessage());
        return refusal;
    }
}
