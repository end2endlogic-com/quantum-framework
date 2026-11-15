package com.e2eq.framework.service.access;

import com.e2eq.framework.model.persistent.morphia.ApplicationInstallRepo;
import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.UserAppRoleRepo;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.ApplicationInstall;
import com.e2eq.framework.model.security.ApplicationLaunchAddress;
import com.e2eq.framework.model.security.ApplicationLifecycleState;
import com.e2eq.framework.model.security.ApplicationRole;
import com.e2eq.framework.model.security.ApplicationSignInRoute;
import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.DeleteOptions;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.bson.Document;
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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application bindings and installs against real Mongo: the repository rules on every save,
 * idempotent upserts by id, the write authority, uninstall, the public catalog over HTTP, and
 * application rows written before the catalog fields.
 */
@QuarkusTest
class ApplicationAccessIntegrationTest extends BaseRepoTest {

    @Inject
    ApplicationRepo applicationRepo;

    @Inject
    ApplicationInstallRepo installRepo;

    @Inject
    UserAppRoleRepo bindingRepo;

    @Inject
    ApplicationAccessService accessService;

    @Inject
    com.e2eq.framework.rest.resources.security.PublicApplicationCatalogResource catalogResource;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    com.e2eq.framework.system.membership.RealmMembershipService membershipService;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    EnvConfigUtils envConfigUtils;

    private String app;
    private String draftApp;
    private String realm;

    @BeforeEach
    void registerApplication() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        app = "bind-planner-" + suffix;
        draftApp = "bind-draft-" + suffix;
        realm = "bind-realm-" + suffix;
        try (SecuritySession ignored = asSystem()) {
            Application application = applicationRepo.ensureRegistered(app, securityUtils.getSystemDataDomain());
            application.setRoles(new ArrayList<>(List.of(
                ApplicationRole.builder().name("planner-user").grantableBy(List.of(ApplicationRole.GRANTABLE_BY_TENANT_ADMIN)).build(),
                ApplicationRole.builder().name("planner-admin").grantableBy(List.of(ApplicationRole.GRANTABLE_BY_REALM_ADMIN)).build())));
            application.setAppVersion("2.3.0");
            application.setOwner("owner-team-" + suffix);
            application.setPackRefs(new ArrayList<>(List.of("pack-" + suffix)));
            application.setLifecycleState(ApplicationLifecycleState.ACTIVE);
            application.setLaunchAddresses(new ArrayList<>(List.of(ApplicationLaunchAddress.builder()
                .environment("development").url("https://" + app + ".example.test").build())));
            application.setSignInRoute(ApplicationSignInRoute.builder().clientId(app + "-web")
                .applicationEntryUrl("https://" + app + ".example.test").authorizationPath("/oauth/authorize")
                .callbackUris(new ArrayList<>(List.of("https://" + app + ".example.test/secret-callback"))).build());
            applicationRepo.save(application);

            Application draft = applicationRepo.ensureRegistered(draftApp, securityUtils.getSystemDataDomain());
            draft.setLifecycleState(ApplicationLifecycleState.DRAFT);
            applicationRepo.save(draft);
        }
    }

    @AfterEach
    void cleanUp() {
        var ds = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm());
        ds.find(UserAppRole.class).filter(Filters.eq("realmRefName", realm)).delete(new DeleteOptions().multi(true));
        ds.find(ApplicationInstall.class).filter(Filters.eq("realmRefName", realm)).delete(new DeleteOptions().multi(true));
        ds.find(com.e2eq.framework.model.security.UserRealmRole.class).filter(Filters.eq("realmRefName", realm))
            .delete(new DeleteOptions().multi(true));
        ds.find(Application.class).filter(Filters.in("refName", List.of(app, draftApp, "legacy-" + app, "bind-preview-" + app)))
            .delete(new DeleteOptions().multi(true));
    }

    // --- repository rules -----------------------------------------------------------------

    @Test
    void installsAndBindingsUpsertByCallerSuppliedId() {
        ObjectId installId = new ObjectId();
        ObjectId bindingId = new ObjectId();
        try (SecuritySession ignored = asSystem()) {
            installRepo.upsertById(install(installId, "tenant-a"));
            installRepo.upsertById(install(installId, "tenant-a"));
            bindingRepo.upsertById(binding(bindingId, "alice", List.of("tenant-a"), null, "planner-user"));
            UserAppRole again = binding(bindingId, "alice", List.of("tenant-a"), null, "planner-user", "planner-admin");
            bindingRepo.upsertById(again);
        }
        assertEquals(installId, installRepo.findActiveWithIgnoreRules(app, realm, "tenant-a").orElseThrow().getId());
        assertEquals(1, installRepo.findByRealmWithIgnoreRules(realm).size());
        List<UserAppRole> rows = bindingRepo.findByApplicationWithIgnoreRules(app, realm, null);
        assertEquals(1, rows.size());
        assertEquals(bindingId, rows.get(0).getId());
        assertEquals(List.of("planner-user", "planner-admin"), rows.get(0).getRoles());
    }

    @Test
    void theRepositoryRefusesBindingsThatBreakTheRules() {
        try (SecuritySession ignored = asSystem()) {
            installRepo.upsertById(install(new ObjectId(), "tenant-a"));
            installRepo.upsertById(install(new ObjectId(), "tenant-b"));

            refused(Code.ROLE_NOT_DECLARED, () -> bindingRepo.save(binding(null, "alice", List.of("tenant-a"), null, "planner-superuser")));
            refused(Code.TENANT_DEFAULT_REQUIRED, () -> bindingRepo.save(binding(null, "alice", List.of("tenant-a", "tenant-b"), null, "planner-user")));
            refused(Code.APPLICATION_NOT_INSTALLED, () -> bindingRepo.save(binding(null, "alice", List.of("tenant-c"), null, "planner-user")));

            bindingRepo.save(binding(null, "alice", List.of("tenant-a", "tenant-b"), "tenant-b", "planner-user"));
            refused(Code.BINDING_INVALID, () -> bindingRepo.save(binding(null, "alice", List.of("tenant-a"), null, "planner-admin")));
        }
        assertEquals(1, bindingRepo.findByApplicationWithIgnoreRules(app, realm, null).size());
    }

    @Test
    void aSecondActiveInstallForTheSameTenantIsRefused() {
        try (SecuritySession ignored = asSystem()) {
            installRepo.upsertById(install(new ObjectId(), "tenant-a"));
            refused(Code.INSTALL_INVALID, () -> installRepo.upsertById(install(new ObjectId(), "tenant-a")));
        }
    }

    @Test
    void uniqueIndexesStopDuplicatesThatSkipTheRepository() {
        try (SecuritySession ignored = asSystem()) {
            installRepo.upsertById(install(new ObjectId(), "tenant-a"));
            bindingRepo.save(binding(null, "alice", List.of("tenant-a"), null, "planner-user"));
        }
        var ds = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm());
        List<String> bindingIndexes = indexNames(ds, UserAppRole.class);
        assertTrue(bindingIndexes.containsAll(List.of(UserAppRoleRepo.UNIQUE_USER_BINDING_INDEX,
            UserAppRoleRepo.UNIQUE_GROUP_BINDING_INDEX)), bindingIndexes.toString());
        assertTrue(indexNames(ds, ApplicationInstall.class).contains(ApplicationInstallRepo.UNIQUE_ACTIVE_INSTALL_INDEX));

        try (SecuritySession ignored = asSystem()) {
            // Straight to the datastore, past requireValid: Mongo itself refuses the second row.
            assertThrows(com.mongodb.MongoWriteException.class,
                () -> ds.save(binding(null, "alice", List.of("tenant-a"), null, "planner-admin")));
            ApplicationInstall second = install(null, "tenant-a");
            assertThrows(com.mongodb.MongoWriteException.class, () -> ds.save(second));
            // Uninstalled rows for the same tenant may repeat.
            ApplicationInstall gone = install(null, "tenant-a");
            gone.setStatus(ApplicationInstall.STATUS_UNINSTALLED);
            ApplicationInstall goneAgain = install(null, "tenant-a");
            goneAgain.setStatus(ApplicationInstall.STATUS_UNINSTALLED);
            ds.save(gone);
            ds.save(goneAgain);
        }
        assertEquals(3, installRepo.findByRealmWithIgnoreRules(realm).size());
    }

    @Test
    void writesThatWouldSkipValidationAreRefused() {
        UserAppRole saved;
        ApplicationInstall installed;
        try (SecuritySession ignored = asSystem()) {
            installed = installRepo.upsertById(install(new ObjectId(), "tenant-a"));
            saved = bindingRepo.save(binding(null, "alice", List.of("tenant-a"), null, "planner-user"));
        }
        ObjectId bindingId = saved.getId();
        ObjectId installId = installed.getId();
        try (SecuritySession ignored = asSystem()) {
            assertThrows(SecurityException.class, () -> bindingRepo.update(bindingId,
                org.apache.commons.lang3.tuple.Pair.of("roles", List.of("planner-superuser"))));
            assertThrows(SecurityException.class, () -> bindingRepo.updateManyByIds(List.of(bindingId),
                org.apache.commons.lang3.tuple.Pair.of("tenantIds", List.of("tenant-z"))));
            assertThrows(SecurityException.class, () -> installRepo.update(installId,
                org.apache.commons.lang3.tuple.Pair.of("status", ApplicationInstall.STATUS_ACTIVE)));
            refused(Code.BINDING_INVALID, () -> bindingRepo.merge(saved));
            refused(Code.BINDING_INVALID, () -> bindingRepo.updateActiveStatus(bindingId,
                com.e2eq.framework.model.persistent.base.ActiveStatus.INACTIVE));
            refused(Code.INSTALL_INVALID, () -> installRepo.merge(installed));
            refused(Code.INSTALL_INVALID, () -> installRepo.updateActiveStatus(installId,
                com.e2eq.framework.model.persistent.base.ActiveStatus.INACTIVE));
        }
        assertEquals(List.of("planner-user"), bindingRepo.findByIdWithIgnoreRules(bindingId).orElseThrow().getRoles());
    }

    private static List<String> indexNames(dev.morphia.MorphiaDatastore ds, Class<?> type) {
        List<String> names = new ArrayList<>();
        String collection = ds.getMapper().getEntityModel(type).collectionName();
        for (Document index : ds.getDatabase().getCollection(collection).listIndexes()) {
            if (Boolean.TRUE.equals(index.get("unique"))) {
                names.add(index.getString("name"));
            }
        }
        return names;
    }

    // --- service authority and uninstall -------------------------------------------------

    @Test
    void thePlatformWritesBindingsAndAnUnprivilegedCallerCannot() {
        try (SecuritySession ignored = asSystem()) {
            accessService.install(realm, install(null, "tenant-a"));
            membershipService.upsertUserRealmRole(com.e2eq.framework.model.security.UserRealmRole.builder()
                .refName("user-realm-role:alice:" + realm).userId("alice").realmRefName(realm)
                .dataDomain(securityUtils.getSystemDataDomain()).status("active").build());
            UserAppRole saved = accessService.putBinding(realm, binding(null, "alice", List.of("tenant-a"), null, "planner-admin"));
            assertEquals(UserAppRole.SOURCE_STAFF, saved.getSource());
            assertEquals(UserAppRole.STATUS_ACTIVE, saved.getStatus());
        }
        PrincipalContext stranger = new PrincipalContext.Builder()
            .withDefaultRealm(realm)
            .withDataDomain(securityUtils.getSystemDataDomain())
            .withUserId("stranger-" + app)
            .withRoles(new String[]{"user"})
            .withScope("AUTHENTICATED")
            .build();
        refused(Code.ROLE_NOT_GRANTABLE, () -> as(stranger, () -> accessService.putBinding(realm, binding(null, "bob", List.of("tenant-a"), null, "planner-user"))));
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(stranger, () -> accessService.install(realm, install(null, "tenant-b"))));
        refused(Code.REALM_MISMATCH, () -> as(stranger, () -> accessService.putBinding("another-realm", binding(null, "bob", List.of("tenant-a"), null))));
    }

    @Test
    void uninstallSuspendsTheTenantsGroupBindingsAndAReinstallReactivatesExactlyThose() {
        ApplicationInstall installed;
        try (SecuritySession ignored = asSystem()) {
            installed = accessService.install(realm, install(null, "tenant-a"));
            accessService.install(realm, install(null, "tenant-b"));
            bindingRepo.save(groupBinding("planners-" + app, "tenant-a", UserAppRole.STATUS_ACTIVE, UserAppRole.SOURCE_STAFF));
            bindingRepo.save(groupBinding("held-" + app, "tenant-a", UserAppRole.STATUS_SUSPENDED, UserAppRole.SOURCE_TENANT_ADMIN));
            bindingRepo.save(groupBinding("others-" + app, "tenant-b", UserAppRole.STATUS_ACTIVE, UserAppRole.SOURCE_TENANT_ADMIN));
            bindingRepo.save(binding(null, "alice", List.of("tenant-a", "tenant-b"), "tenant-a", "planner-user"));

            ApplicationInstall uninstalled = accessService.uninstall(realm, installed.getId());
            assertEquals(ApplicationInstall.STATUS_UNINSTALLED, uninstalled.getStatus());
        }
        assertFalse(installRepo.isInstalled(app, realm, "tenant-a"));
        assertTrue(installRepo.isInstalled(app, realm, "tenant-b"));
        assertEquals(4, bindingRepo.findByApplicationWithIgnoreRules(app, realm, null).size(), "nothing is deleted");
        UserAppRole planners = groupRow("planners-" + app);
        assertEquals(UserAppRole.STATUS_SUSPENDED, planners.getStatus(), "a staff-written binding is suspended too");
        assertEquals(UserAppRole.SUSPENDED_BY_UNINSTALL + installed.getId(), planners.getSuspendedBy());
        assertNull(groupRow("held-" + app).getSuspendedBy(), "an already suspended binding keeps its reason");
        assertEquals(UserAppRole.STATUS_ACTIVE, groupRow("others-" + app).getStatus(), "another tenant's binding is untouched");

        try (SecuritySession ignored = asSystem()) {
            accessService.install(realm, install(null, "tenant-a"));
        }
        assertEquals(UserAppRole.STATUS_ACTIVE, groupRow("planners-" + app).getStatus(), "the uninstall's suspension is undone");
        assertNull(groupRow("planners-" + app).getSuspendedBy());
        assertEquals(UserAppRole.STATUS_SUSPENDED, groupRow("held-" + app).getStatus(), "only the uninstall's suspensions are undone");
    }

    @Test
    void replayingAnUninstallOfAStaleIdSuspendsNothing() {
        ApplicationInstall first;
        ApplicationInstall second;
        try (SecuritySession ignored = asSystem()) {
            first = accessService.install(realm, install(null, "tenant-a"));
            bindingRepo.save(groupBinding("planners-" + app, "tenant-a", UserAppRole.STATUS_ACTIVE, UserAppRole.SOURCE_STAFF));
            accessService.uninstall(realm, first.getId());
            second = accessService.install(realm, install(null, "tenant-a"));
            assertFalse(first.getId().equals(second.getId()), "the reinstall is a new install");
            assertEquals(UserAppRole.STATUS_ACTIVE, groupRow("planners-" + app).getStatus());

            // The first uninstall, replayed: the install is already uninstalled, so nothing changes.
            assertEquals(ApplicationInstall.STATUS_UNINSTALLED, accessService.uninstall(realm, first.getId()).getStatus());
        }
        assertEquals(UserAppRole.STATUS_ACTIVE, groupRow("planners-" + app).getStatus(),
            "a stale uninstall does not suspend the bindings the reinstall reactivated");
        assertNull(groupRow("planners-" + app).getSuspendedBy());
        assertTrue(installRepo.isInstalled(app, realm, "tenant-a"));
        assertEquals(ApplicationInstall.STATUS_ACTIVE, installRepo.findByIdWithIgnoreRules(second.getId()).orElseThrow().getStatus());
    }

    // --- public catalog -------------------------------------------------------------------

    @Test
    void thePublicCatalogIsUnauthenticatedAndCarriesCatalogFieldsOnly() {
        String previewApp = "bind-preview-" + app;
        try (SecuritySession ignored = asSystem()) {
            Application preview = applicationRepo.ensureRegistered(previewApp, securityUtils.getSystemDataDomain());
            preview.setLifecycleState(ApplicationLifecycleState.PREVIEW);
            applicationRepo.save(preview);
        }
        catalogResource.invalidate();
        String body = given().when().get("/public/applications").then().statusCode(200).extract().asString();
        JsonPath json = new JsonPath(body);
        List<Map<String, Object>> entries = json.getList("findAll { it.refName == '" + app + "' }");
        assertEquals(1, entries.size(), body);
        Map<String, Object> entry = entries.get(0);
        assertEquals(Set.of("refName", "displayName", "appVersion", "lifecycleState", "signIn"), entry.keySet());
        assertEquals("2.3.0", entry.get("appVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> signIn = (Map<String, Object>) entry.get("signIn");
        assertEquals(Set.of("clientId", "applicationEntryUrl", "authorizationPath"), signIn.keySet());
        assertFalse(body.contains(draftApp), "a draft application is not public");
        assertFalse(body.contains(previewApp), "a preview application is not listed");
        assertFalse(body.contains("launchAddresses") || body.contains("development"), "launch addresses are not public");
        assertFalse(body.contains("secret-callback"), "the callback allowlist is not public");
        assertFalse(body.contains("owner-team-"), "the owner is not public");
        assertFalse(body.contains("pack-"), "pack refs are not public");
        assertFalse(body.contains("planner-admin"), "roles are not public");
    }

    // --- install list over HTTP --------------------------------------------------------------

    @Test
    @io.quarkus.test.security.TestSecurity(user = "system@system.com", roles = {"admin", "system"})
    void thePlatformListsAnotherRealmsInstallsOverHttp() {
        // The test identity is the framework's system user, so it resolves to the platform whatever roles it names;
        // the non-platform refusal is covered at the service in ApplicationAccessAuthorityTest.
        ObjectId a = new ObjectId();
        ObjectId b = new ObjectId();
        try (SecuritySession ignored = asSystem()) {
            installRepo.upsertById(install(a, "tenant-a"));
            installRepo.upsertById(install(b, "tenant-b"));
        }
        JsonPath all = given().queryParam("realmRefName", realm).queryParam("applicationId", app)
            .when().get("/api/security/applicationInstall/list").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(a.toString(), b.toString()), all.getList("id"));
        JsonPath one = given().queryParam("realmRefName", realm).queryParam("applicationId", app).queryParam("tenantId", "tenant-b")
            .when().get("/api/security/applicationInstall/list").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(b.toString()), one.getList("id"));
        assertEquals(List.of(), given().queryParam("realmRefName", realm).queryParam("tenantId", "tenant-none")
            .when().get("/api/security/applicationInstall/list").then().statusCode(200).extract().jsonPath().getList("$"));
        assertEquals("APPLICATION_UNKNOWN", given().queryParam("realmRefName", realm).queryParam("applicationId", "unregistered-" + app)
            .when().get("/api/security/applicationInstall/list").then().statusCode(400).extract().jsonPath().getString("errorCode"));
    }

    // --- rows written before the catalog fields --------------------------------------------

    @Test
    void anApplicationRowWrittenBeforeTheCatalogFieldsStillLoads() {
        // Write a slice-1 shaped row, then strip every catalog field from the stored document, as a
        // row written before they existed.
        try (SecuritySession ignored = asSystem()) {
            Application legacy = applicationRepo.ensureRegistered("legacy-" + app, securityUtils.getSystemDataDomain());
            legacy.setRoles(new ArrayList<>(List.of(ApplicationRole.builder().name("legacy-user")
                .grantableBy(List.of(ApplicationRole.GRANTABLE_BY_TENANT_ADMIN)).build())));
            legacy.setOwnerRoles(new ArrayList<>(List.of("legacy-user")));
            applicationRepo.save(legacy);
        }
        var ds = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm());
        String collection = ds.getMapper().getEntityModel(Application.class).collectionName();
        var unset = new Document();
        for (String field : List.of("appVersion", "owner", "lifecycleState", "packRefs", "launchAddresses", "signInRoute")) {
            unset.append(field, "");
        }
        assertEquals(1, ds.getDatabase().getCollection(collection)
            .updateOne(new Document("refName", "legacy-" + app), new Document("$unset", unset)).getMatchedCount());
        Document raw = ds.getDatabase().getCollection(collection).find(new Document("refName", "legacy-" + app)).first();
        assertFalse(raw.containsKey("packRefs") || raw.containsKey("launchAddresses"), raw.toJson());

        Application loaded = applicationRepo.findByRefNameWithIgnoreRules("legacy-" + app).orElseThrow();
        assertEquals(1, loaded.getRoles().size());
        assertNull(loaded.getLifecycleState());
        assertNull(loaded.getAppVersion());
        assertNull(loaded.getSignInRoute());
        assertTrue(loaded.getLaunchAddresses() == null || loaded.getLaunchAddresses().isEmpty());
    }

    // --- harness ---

    private ApplicationInstall install(ObjectId id, String tenant) {
        ApplicationInstall install = new ApplicationInstall();
        install.setId(id);
        install.setApplicationId(app);
        install.setRealmRefName(realm);
        install.setTenantId(tenant);
        install.setStatus(ApplicationInstall.STATUS_ACTIVE);
        return install;
    }

    private UserAppRole binding(ObjectId id, String user, List<String> tenants, String defaultTenant, String... roles) {
        UserAppRole binding = new UserAppRole();
        binding.setId(id);
        binding.setUserId(user);
        binding.setApplicationId(app);
        binding.setRealmRefName(realm);
        binding.setTenantIds(new ArrayList<>(tenants));
        binding.setDefaultTenantId(defaultTenant);
        binding.setRoles(new ArrayList<>(List.of(roles)));
        return binding;
    }

    private UserAppRole groupBinding(String group, String tenant, String status, String source) {
        UserAppRole binding = new UserAppRole();
        binding.setGroupRefName(group);
        binding.setApplicationId(app);
        binding.setRealmRefName(realm);
        binding.setTenantIds(new ArrayList<>(List.of(tenant)));
        binding.setRoles(new ArrayList<>(List.of("planner-user")));
        binding.setStatus(status);
        binding.setSource(source);
        return binding;
    }

    private UserAppRole groupRow(String group) {
        return bindingRepo.findByApplicationWithIgnoreRules(app, realm, null).stream()
            .filter(binding -> group.equals(binding.getGroupRefName()))
            .findFirst().orElseThrow();
    }

    private <T> T as(PrincipalContext principal, java.util.function.Supplier<T> call) {
        return SecurityCallScope.runWithContexts(principal, rContext, call);
    }

    private static void refused(Code expected, Executable call) {
        ApplicationAccessException refusal = assertThrows(ApplicationAccessException.class, call);
        assertEquals(expected, refusal.code(), refusal.getMessage());
    }
}
