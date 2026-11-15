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
    private static final String POOLED = "bind-authority-pooled";
    private static final String OTHER_REALM = "bind-authority-other";
    private static final String TENANT_A = "tenant-a.bind-authority";
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

    // ---- remote directory -----------------------------------------------------------------

    @Test
    void underARemoteDirectoryBindingAndInstallCallsRefuse() {
        java.util.Optional<String> before = accessDirectoryMode();
        setAccessDirectoryMode(java.util.Optional.of("remote"));
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
            refused(Code.DIRECTORY_REMOTE, () -> asSystem(() -> accessService.listBindings(POOLED, app, TENANT_A)));
        } finally {
            setAccessDirectoryMode(before);
        }
        assertEquals(0, installRepo.findByRealmWithIgnoreRules(POOLED).stream()
            .filter(install -> app.equals(install.getApplicationId())).count());
    }

    // ---- fixtures -------------------------------------------------------------------------

    @Inject
    com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory accessDirectory;

    @SuppressWarnings("unchecked")
    private java.util.Optional<String> accessDirectoryMode() {
        try {
            return (java.util.Optional<String>) directoryModeField().get(unproxied());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setAccessDirectoryMode(java.util.Optional<String> mode) {
        try {
            directoryModeField().set(unproxied(), mode);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object unproxied() {
        return accessDirectory instanceof io.quarkus.arc.ClientProxy proxy ? proxy.arc_contextualInstance() : accessDirectory;
    }

    private static java.lang.reflect.Field directoryModeField() throws NoSuchFieldException {
        java.lang.reflect.Field field = com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory.class
            .getDeclaredField("directoryMode");
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
