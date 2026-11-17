package com.e2eq.framework.service.access;

import com.e2eq.framework.model.auth.provider.jwtToken.TokenUtils;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.ApplicationInstallRepo;
import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.ApplicationInstall;
import com.e2eq.framework.model.security.ApplicationTenancy;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.DeleteOptions;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
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
 * The one widening of install authority: an application's own service identity (principal scope
 * {@link PrincipalContext#SCOPE_SERVICE}, its application from the token's {@code azp}) may install
 * and uninstall that same application in a realm the application declares. Everything else a
 * service identity asks for is refused with a typed code: another application, a wildcard, no
 * application, a realm the application does not declare, a request made for someone else. A user
 * principal holding the same roles and application stays where it was. Runs with
 * application-scoped policy evaluation required, as Quantum Auth does.
 */
@QuarkusTest
@TestProfile(ApplicationServiceSelfInstallTest.RequireApplicationProfile.class)
class ApplicationServiceSelfInstallTest {

    public static class RequireApplicationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quantum.security.policy-scope.require-application", "true",
                "quantum.migration.application-id", "self-install-console");
        }
    }

    static final String HTTP_APPLICATION = "self-install-http-app";
    static final String HTTP_SERVICE = "svc-self-install-http@service.example";
    static final String DECLARED_REALM = "self-install-declared";
    static final String OTHER_DECLARED_REALM = "self-install-declared-2";
    static final String UNDECLARED_REALM = "self-install-undeclared";

    @Inject ApplicationAccessService accessService;
    @Inject ApplicationRepo applicationRepo;
    @Inject ApplicationInstallRepo installRepo;
    @Inject MorphiaDataStoreWrapper morphiaDataStoreWrapper;
    @Inject EnvConfigUtils envConfigUtils;
    @Inject SecurityUtils securityUtils;
    @Inject RuleContext ruleContext;

    private String app;
    private String otherApp;
    private String pooledApp;
    private final List<String> applications = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        app = register("self-install-" + suffix, ApplicationTenancy.PLACEMENT_REALMS, DECLARED_REALM, OTHER_DECLARED_REALM);
        otherApp = register("self-install-other-" + suffix, ApplicationTenancy.PLACEMENT_REALMS, DECLARED_REALM);
        pooledApp = register("self-install-pooled-" + suffix, ApplicationTenancy.PLACEMENT_POOLED);
        register(HTTP_APPLICATION, ApplicationTenancy.PLACEMENT_REALMS, DECLARED_REALM);
    }

    @AfterEach
    void tearDown() {
        var system = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm());
        system.find(ApplicationInstall.class).filter(Filters.in("applicationId", applications)).delete(new DeleteOptions().multi(true));
        system.find(Application.class).filter(Filters.in("refName", applications)).delete(new DeleteOptions().multi(true));
        applications.clear();
    }

    // ---- allowed ------------------------------------------------------------------------------

    @Test
    void anApplicationsServiceIdentityInstallsAndUninstallsItselfInARealmItDeclares() {
        PrincipalContext service = service("svc-" + app, app);
        ApplicationInstall installed = as(service, () -> accessService.install(systemRealm(), install(app, DECLARED_REALM)));

        assertEquals(ApplicationInstall.STATUS_ACTIVE, installed.getStatus());
        assertEquals(app, installed.getApplicationId());
        assertEquals(DECLARED_REALM, installed.getRealmRefName());
        assertEquals(DECLARED_REALM, installed.getTenantId());
        assertEquals("svc-" + app, installed.getInstalledBy());
        // A repeat (the registrar's every unregistered boot) returns the active install unchanged.
        assertEquals(installed.getId(),
            as(service, () -> accessService.install(systemRealm(), install(app, DECLARED_REALM))).getId());
        // Its second declared realm too.
        assertEquals(OTHER_DECLARED_REALM,
            as(service, () -> accessService.install(systemRealm(), install(app, OTHER_DECLARED_REALM))).getRealmRefName());

        ApplicationInstall uninstalled = as(service, () -> accessService.uninstall(systemRealm(), installed.getId()));
        assertEquals(ApplicationInstall.STATUS_UNINSTALLED, uninstalled.getStatus());
        assertEquals("svc-" + app, uninstalled.getUninstalledBy());
    }

    // ---- refused: application -----------------------------------------------------------------

    @Test
    void anotherApplicationIsRefused() {
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(service("svc-" + app, app),
            () -> accessService.install(systemRealm(), install(otherApp, DECLARED_REALM))));
        assertNoInstall(otherApp);
    }

    @Test
    void aServiceIdentityWithoutAnApplicationIsRefused() {
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(service("svc-" + app, null),
            () -> accessService.install(systemRealm(), install(app, DECLARED_REALM))));
        assertNoInstall(app);
    }

    @Test
    void aWildcardApplicationIsRefused() {
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(service("svc-" + app, app),
            () -> accessService.install(systemRealm(), install("*", DECLARED_REALM))));
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(service("svc-any", "*"),
            () -> accessService.install(systemRealm(), install("*", DECLARED_REALM))));
        assertNoInstall(app);
    }

    @Test
    void aRequestMadeForSomeoneElseIsRefused() {
        PrincipalContext base = service("svc-" + app, app);
        PrincipalContext onBehalf = new PrincipalContext.Builder()
            .withDefaultRealm(base.getDefaultRealm()).withApplicationId(app).withDataDomain(base.getDataDomain())
            .withUserId(base.getUserId()).withRoles(base.getRoles()).withScope(base.getScope())
            .withActingOnBehalfOfUserId("someone@tenant.example").build();
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(onBehalf,
            () -> accessService.install(systemRealm(), install(app, DECLARED_REALM))));
        assertNoInstall(app);
    }

    // ---- refused: realm -----------------------------------------------------------------------

    @Test
    void aRealmTheApplicationDoesNotDeclareIsRefused() {
        PrincipalContext service = service("svc-" + app, app);
        refused(Code.REALM_MISMATCH, () -> as(service,
            () -> accessService.install(systemRealm(), install(app, UNDECLARED_REALM))));
        refused(Code.REALM_MISMATCH, () -> as(service,
            () -> accessService.install(systemRealm(), install(app, systemRealm()))));
        refused(Code.REALM_MISMATCH, () -> as(service,
            () -> accessService.install(systemRealm(), install(app, null))));
        assertNoInstall(app);
    }

    @Test
    void anApplicationThatDeclaresNoRealmByNameIsRefused() {
        refused(Code.REALM_MISMATCH, () -> as(service("svc-" + pooledApp, pooledApp),
            () -> accessService.install(systemRealm(), install(pooledApp, pooledApp + "-P1"))));
        assertNoInstall(pooledApp);
    }

    // ---- refused: not a service identity ------------------------------------------------------

    @Test
    void aUserPrincipalWithTheSameRolesAndApplicationIsRefused() {
        PrincipalContext service = service("user-" + app + "@tenant.example", app);
        PrincipalContext user = new PrincipalContext.Builder()
            .withDefaultRealm(service.getDefaultRealm()).withApplicationId(app).withDataDomain(service.getDataDomain())
            .withUserId(service.getUserId()).withRoles(service.getRoles()).withScope("AUTHENTICATED").build();
        refused(Code.REALM_MISMATCH, () -> as(user,
            () -> accessService.install(systemRealm(), install(app, DECLARED_REALM))));
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(user,
            () -> accessService.install(systemRealm(), install(app, systemRealm()))));
        assertNoInstall(app);
    }

    // ---- install ids and uninstall ------------------------------------------------------------

    @Test
    void aServiceIdentityReachesOnlyItsOwnApplicationsInstallsInItsDeclaredRealms() {
        ApplicationInstall ofOther = asSystem(() -> accessService.install(DECLARED_REALM, install(otherApp, DECLARED_REALM)));
        ApplicationInstall undeclared = asSystem(() -> accessService.install(UNDECLARED_REALM, install(app, UNDECLARED_REALM)));
        PrincipalContext service = service("svc-" + app, app);

        refused(Code.NOT_FOUND, () -> as(service, () -> accessService.uninstall(systemRealm(), ofOther.getId())));
        refused(Code.NOT_FOUND, () -> as(service, () -> accessService.uninstall(systemRealm(), undeclared.getId())));
        // Without an application the request is refused before any install is read.
        refused(Code.APPLICATION_SCOPE_REQUIRED, () -> as(service("svc-" + otherApp, null),
            () -> accessService.uninstall(systemRealm(), ofOther.getId())));
        // A service identity installs without an id: neither another application's install id nor a
        // fresh one is accepted, so no id can reach another install.
        ApplicationInstall byId = install(app, DECLARED_REALM);
        byId.setId(ofOther.getId());
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(service, () -> accessService.install(systemRealm(), byId)));
        ApplicationInstall freshId = install(app, DECLARED_REALM);
        freshId.setId(new org.bson.types.ObjectId());
        refused(Code.INSTALL_NOT_AUTHORIZED, () -> as(service, () -> accessService.install(systemRealm(), freshId)));
        assertEquals(java.util.Optional.empty(), installRepo.findByIdWithIgnoreRules(freshId.getId()));

        assertEquals(ApplicationInstall.STATUS_ACTIVE, installRepo.findByIdWithIgnoreRules(ofOther.getId()).orElseThrow().getStatus());
        assertEquals(ApplicationInstall.STATUS_ACTIVE, installRepo.findByIdWithIgnoreRules(undeclared.getId()).orElseThrow().getStatus());
        assertEquals(otherApp, installRepo.findByIdWithIgnoreRules(ofOther.getId()).orElseThrow().getApplicationId());
    }

    // ---- pre-authorization (the framework's default rule) -------------------------------------

    @Test
    void preAuthorizationAdmitsAServiceIdentityToInstallAndUninstallOnly() {
        PrincipalContext service = service("svc-" + app, app);
        assertEquals(RuleEffect.ALLOW, decision(service, app, "install"));
        assertEquals(RuleEffect.ALLOW, decision(service, app, "uninstall"));
        assertEquals(RuleEffect.DENY, decision(service, app, "list"));
    }

    @Test
    void preAuthorizationRefusesAUserPrincipalHoldingTheSystemRole() {
        PrincipalContext service = service("user-" + app + "@tenant.example", app);
        PrincipalContext user = new PrincipalContext.Builder()
            .withDefaultRealm(service.getDefaultRealm()).withApplicationId(app).withDataDomain(service.getDataDomain())
            .withUserId(service.getUserId()).withRoles(service.getRoles()).withScope("AUTHENTICATED").build();
        assertEquals(RuleEffect.DENY, decision(user, app, "install"));
        assertEquals(RuleEffect.DENY, decision(user, app, "uninstall"));
    }

    // ---- over HTTP, with a real service token ------------------------------------------------

    /**
     * A token the framework mints for a service ({@code TokenUtils.generateServiceToken}:
     * {@code token_type=service}, its application as {@code azp}, no {@code aud}, no realm), sent
     * with no {@code X-Realm}. {@code @TestSecurity} cannot stand in here: the service path of
     * {@code SecurityFilter} needs the bearer token itself.
     */
    @Test
    void overHttpTheServiceIdentityInstallsOnlyItsOwnApplicationInItsDeclaredRealm() throws Exception {
        String token = TokenUtils.generateServiceToken(HTTP_SERVICE, Set.of("system", "user"), HTTP_APPLICATION,
            TokenUtils.expiresAt(300), "https://example.com/issuer");

        Response installed = post(token, HTTP_APPLICATION, DECLARED_REALM);
        assertEquals(200, installed.statusCode(), installed.asString());
        assertEquals(HTTP_APPLICATION, installed.jsonPath().getString("applicationId"));
        assertEquals(DECLARED_REALM, installed.jsonPath().getString("realmRefName"));
        assertEquals("active", installed.jsonPath().getString("status"));

        Response other = post(token, app, DECLARED_REALM);
        assertEquals(403, other.statusCode(), other.asString());
        assertEquals("INSTALL_NOT_AUTHORIZED", other.jsonPath().getString("errorCode"), other.asString());

        Response undeclared = post(token, HTTP_APPLICATION, UNDECLARED_REALM);
        assertEquals(403, undeclared.statusCode(), undeclared.asString());
        assertEquals("REALM_MISMATCH", undeclared.jsonPath().getString("errorCode"), undeclared.asString());

        String id = installed.jsonPath().getString("id");
        Response uninstalled = RestAssured.given().auth().oauth2(token).contentType(ContentType.JSON).body("")
            .post("/api/security/applicationInstall/id/" + id + "/uninstall");
        assertEquals(200, uninstalled.statusCode(), uninstalled.asString());
        assertEquals("uninstalled", uninstalled.jsonPath().getString("status"));
    }

    @Test
    void overHttpAServiceTokenWithoutAnApplicationIsRefused() throws Exception {
        String token = TokenUtils.generateServiceToken(HTTP_SERVICE, Set.of("system", "user"), null,
            TokenUtils.expiresAt(300), "https://example.com/issuer");
        Response refused = post(token, HTTP_APPLICATION, DECLARED_REALM);
        assertEquals(400, refused.statusCode(), refused.asString());
        assertEquals("APPLICATION_SCOPE_REQUIRED", refused.jsonPath().getString("code"), refused.asString());
        assertNoInstall(HTTP_APPLICATION);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Response post(String token, String application, String realm) {
        return RestAssured.given()
            .auth().oauth2(token)
            .contentType(ContentType.JSON)
            .body(Map.of("applicationId", application, "realmRefName", realm, "tenantId", realm, "status", "active"))
            .post("/api/security/applicationInstall");
    }

    private RuleEffect decision(PrincipalContext principal, String application, String action) {
        ResourceContext resource = new ResourceContext.Builder()
            .withRealm(systemRealm()).withApplicationId(application)
            .withArea("security").withFunctionalDomain("application_install").withAction(action).build();
        return ruleContext.checkRules(principal, resource, RuleEffect.DENY).getFinalEffect();
    }

    private String register(String refName, String placement, String... realms) {
        applications.add(refName);
        asSystem(() -> {
            Application application = applicationRepo.ensureRegistered(refName, securityUtils.getSystemDataDomain());
            application.setTenancy(ApplicationTenancy.builder()
                .placement(placement).realms(new ArrayList<>(List.of(realms))).build());
            return applicationRepo.save(application);
        });
        return refName;
    }

    private String systemRealm() {
        return envConfigUtils.getSystemRealm();
    }

    /**
     * The principal {@code SecurityFilter} builds for a {@code token_type=service} token: the system
     * realm and data domain, roles {@code system} and {@code user}, its {@code azp} as application.
     */
    private PrincipalContext service(String userId, String application) {
        DataDomain system = securityUtils.getSystemDataDomain();
        return new PrincipalContext.Builder()
            .withDefaultRealm(systemRealm())
            .withApplicationId(application)
            .withDataDomain(system)
            .withUserId(userId)
            .withRoles(new String[]{"system", "user"})
            .withScope(PrincipalContext.SCOPE_SERVICE)
            .build();
    }

    private static ApplicationInstall install(String application, String realm) {
        ApplicationInstall install = new ApplicationInstall();
        install.setApplicationId(application);
        install.setRealmRefName(realm);
        install.setTenantId(realm == null ? "self-install-tenant" : realm);
        install.setStatus(ApplicationInstall.STATUS_ACTIVE);
        return install;
    }

    private <T> T as(PrincipalContext principal, Supplier<T> action) {
        ResourceContext resource = new ResourceContext.Builder()
            .withRealm(systemRealm()).withApplicationId(principal.getApplicationId())
            .withArea("security").withFunctionalDomain("application_install").withAction("install").build();
        return SecurityCallScope.runWithContexts(principal, resource, action);
    }

    private <T> T asSystem(Supplier<T> action) {
        PrincipalContext base = securityUtils.getSystemPrincipalContext();
        PrincipalContext system = new PrincipalContext.Builder()
            .withDefaultRealm(base.getDefaultRealm()).withApplicationId(app == null ? "self-install-setup" : app)
            .withDomainContext(base.getDomainContext()).withDataDomain(base.getDataDomain())
            .withUserId(base.getUserId()).withRoles(base.getRoles()).withScope(base.getScope()).build();
        ResourceContext resource = new ResourceContext.Builder()
            .withRealm(systemRealm()).withApplicationId(system.getApplicationId())
            .withArea(securityUtils.getSystemSecurityResourceContext().getArea())
            .withFunctionalDomain(securityUtils.getSystemSecurityResourceContext().getFunctionalDomain())
            .withAction(securityUtils.getSystemSecurityResourceContext().getAction()).build();
        return SecurityCallScope.runWithContexts(system, resource, action);
    }

    private void assertNoInstall(String application) {
        assertEquals(0, morphiaDataStoreWrapper.getDataStore(systemRealm()).find(ApplicationInstall.class)
            .filter(Filters.eq("applicationId", application)).count(), "no install of " + application + " was written");
    }

    private static ApplicationAccessException refused(Code expected, Executable action) {
        ApplicationAccessException refusal = assertThrows(ApplicationAccessException.class, action);
        assertEquals(expected, refusal.code(), refusal.getMessage());
        return refusal;
    }
}
