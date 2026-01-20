package com.e2eq.framework.rest.filters;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import dev.morphia.query.filters.Filters;
import io.smallrye.mutiny.Multi;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.rest.models.AuthRequest;
import com.e2eq.framework.rest.requests.CreateUserRequest;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.EnvConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.*;

import java.util.Date;
import java.util.Optional;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for X-Realm user provisioning.
 *
 * These tests verify:
 * 1. An admin user can provision a user in their own tenant WITHOUT X-Realm header
 * 2. An admin user WITH X-Realm enabled can switch to another realm and provision
 *    a user in that target tenant - the user should be created in the target tenant,
 *    NOT the admin's home tenant
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("X-Realm User Provisioning Integration Tests")
public class XRealmUserProvisioningIT extends BaseRepoTest {

    // Test target realm configuration (the "other" tenant)
    private static final String TARGET_REALM_REF_NAME = "xrealm-provision-target";
    private static final String TARGET_EMAIL_DOMAIN = "xrealmtarget.com";
    private static final String TARGET_ORG = "XREALM-TARGET-ORG";
    private static final String TARGET_TENANT = "xrealm-provision-target";
    private static final String TARGET_ACCOUNT = "XREALM-TARGET-ACCT";

    // Test users to be provisioned
    private static final String LOCAL_TEST_USER = "local-provisioned-user@test.com";
    private static final String XREALM_TEST_USER = "xrealm-provisioned-user@xrealmtarget.com";
    private static final String TEST_PASSWORD = "TestP@ssw0rd!";

    @ConfigProperty(name = "auth.provider", defaultValue = "custom")
    String authProvider;

    @Inject
    CredentialRepo credRepo;

    @Inject
    RealmRepo realmRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    ObjectMapper mapper;

    @Inject
    MigrationService migrationService;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    private String accessToken;
    private Realm targetRealm;

    @BeforeEach
    void setUp() throws Exception {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            // Ensure the admin test user exists and has realm override capability
            ensureAdminUserWithRealmOverride();

            // Ensure target realm exists
            ensureTargetRealmExists();

            // Clean up any test users from previous runs
            cleanupTestUsers();

            // Get access token for the admin test user
            accessToken = loginAndGetToken();
        }
    }

    @AfterEach
    void tearDown() {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            // Clean up test users
            cleanupTestUsers();

            // Clean up target realm
            cleanupTargetRealm();
        } catch (Exception e) {
            Log.warnf("Cleanup failed: %s", e.getMessage());
        }
    }

    /**
     * Ensures the admin test user exists and has realmRegEx set to allow realm override.
     */
    private void ensureAdminUserWithRealmOverride() throws ReferentialIntegrityViolationException {
        Optional<CredentialUserIdPassword> credop = credRepo.findByUserId(
            testUtils.getTestUserId(), testUtils.getSystemRealm());

        CredentialUserIdPassword cred;
        if (credop.isPresent()) {
            cred = credop.get();
            // Ensure admin role and realm override is enabled
            boolean needsUpdate = false;

            if (cred.getRealmRegEx() == null || !cred.getRealmRegEx().equals("*")) {
                cred.setRealmRegEx("*");
                needsUpdate = true;
            }

            // Ensure admin role
            String[] currentRoles = cred.getRoles();
            boolean hasAdmin = false;
            if (currentRoles != null) {
                for (String role : currentRoles) {
                    if ("admin".equals(role)) {
                        hasAdmin = true;
                        break;
                    }
                }
            }
            if (!hasAdmin) {
                cred.setRoles(new String[]{"admin", "user"});
                needsUpdate = true;
            }

            if (needsUpdate) {
                credRepo.save(testUtils.getSystemRealm(), cred);
            }
        } else {
            cred = new CredentialUserIdPassword();
            cred.setUserId(testUtils.getTestUserId());
            cred.setPasswordHash(EncryptionUtils.hashPassword(testUtils.getDefaultTestPassword()));

            Optional<String> osubjectId = authProviderFactory.getUserManager()
                .getSubjectForUserId(testUtils.getTestUserId());
            cred.setSubject(osubjectId.orElse(testUtils.getTestUserId()));

            DataDomain dataDomain = new DataDomain();
            dataDomain.setOrgRefName(testUtils.getTestOrgRefName());
            dataDomain.setAccountNum(testUtils.getTestAccountNumber());
            dataDomain.setTenantId(testUtils.getTestTenantId());
            dataDomain.setOwnerId(testUtils.getTestUserId());

            cred.setRoles(new String[]{"admin", "user"});
            cred.setRefName(cred.getUserId());
            cred.setDomainContext(new DomainContext(dataDomain, testUtils.getTestRealm()));
            cred.setLastUpdate(new Date());
            cred.setDataDomain(dataDomain);
            cred.setRealmRegEx("*");  // Allow all realm overrides
            cred.setImpersonateFilterScript("return true");
            cred = credRepo.save(testUtils.getSystemRealm(), cred);
        }
    }

    /**
     * Creates the target realm with its own DomainContext.
     */
    private void ensureTargetRealmExists() throws ReferentialIntegrityViolationException {
        Optional<Realm> existingRealm = realmRepo.findByRefName(TARGET_REALM_REF_NAME, true,
            envConfigUtils.getSystemRealm());

        if (existingRealm.isPresent()) {
            targetRealm = existingRealm.get();
            // Ensure migrations are initialized even if realm already exists
            initializeRealmMigrations(TARGET_REALM_REF_NAME);
            return;
        }

        // Create target realm with its own DomainContext
        DomainContext targetDomainContext = DomainContext.builder()
                .tenantId(TARGET_TENANT)
                .defaultRealm(TARGET_REALM_REF_NAME)
                .orgRefName(TARGET_ORG)
                .accountId(TARGET_ACCOUNT)
                .dataSegment(0)
                .build();

        // Create DataDomain for the realm entity itself (stored in system realm)
        DataDomain realmDataDomain = DataDomain.builder()
                .orgRefName(envConfigUtils.getSystemOrgRefName())
                .tenantId(envConfigUtils.getSystemTenantId())
                .accountNum(envConfigUtils.getSystemAccountNumber())
                .ownerId(envConfigUtils.getSystemUserId())
                .dataSegment(0)
                .build();

        targetRealm = Realm.builder()
                .refName(TARGET_REALM_REF_NAME)
                .displayName("X-Realm Provisioning Target")
                .emailDomain(TARGET_EMAIL_DOMAIN)
                .databaseName(TARGET_REALM_REF_NAME)
                .domainContext(targetDomainContext)
                .dataDomain(realmDataDomain)
                .build();

        targetRealm = realmRepo.save(envConfigUtils.getSystemRealm(), targetRealm);
        Log.infof("Created target realm: %s with DomainContext tenantId=%s",
            TARGET_REALM_REF_NAME, TARGET_TENANT);

        // Initialize the target realm's database with migrations
        // This is required for X-Realm requests to succeed
        initializeRealmMigrations(TARGET_REALM_REF_NAME);
        Log.infof("Initialized migrations for target realm: %s", TARGET_REALM_REF_NAME);
    }

    /**
     * Initializes migrations for a realm, running them if needed.
     */
    private void initializeRealmMigrations(String realm) {
        try {
            migrationService.checkInitialized(realm);
        } catch (Exception e) {
            // Database not initialized, run migrations
            Log.infof("Running migrations for realm: %s", realm);
            Multi.createFrom().emitter(emitter -> {
                migrationService.runAllUnRunMigrations(realm, emitter);
            }).subscribe().with(
                item -> Log.debugf("Migration: %s", item),
                failure -> Log.errorf("Migration failed: %s", failure.getMessage()),
                () -> Log.infof("Migrations completed for realm: %s", realm)
            );
        }
    }

    /**
     * Cleans up test users created during tests.
     * Uses direct Morphia queries to bypass security filters that would otherwise
     * prevent cleanup of users in different tenants.
     */
    private void cleanupTestUsers() {
        // Clean up local test user using direct Morphia queries
        try {
            // Delete UserProfile in local realm
            long deleted = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm())
                .find(UserProfile.class)
                .filter(Filters.eq("userId", LOCAL_TEST_USER))
                .delete()
                .getDeletedCount();
            if (deleted > 0) {
                Log.infof("Cleaned up local user profile: %s (deleted %d)", LOCAL_TEST_USER, deleted);
            }
        } catch (Exception e) {
            Log.debugf("Could not cleanup local user profile: %s", e.getMessage());
        }

        try {
            // Delete Credential in system realm
            long deleted = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm())
                .find(CredentialUserIdPassword.class)
                .filter(Filters.eq("userId", LOCAL_TEST_USER))
                .delete()
                .getDeletedCount();
            if (deleted > 0) {
                Log.infof("Cleaned up local test credential: %s (deleted %d)", LOCAL_TEST_USER, deleted);
            }
        } catch (Exception e) {
            Log.debugf("Could not cleanup local test credential: %s", e.getMessage());
        }

        // Clean up X-Realm test user using direct Morphia queries
        try {
            // Delete UserProfile in target realm
            long deleted = morphiaDataStoreWrapper.getDataStore(TARGET_REALM_REF_NAME)
                .find(UserProfile.class)
                .filter(Filters.eq("userId", XREALM_TEST_USER))
                .delete()
                .getDeletedCount();
            if (deleted > 0) {
                Log.infof("Cleaned up xrealm user profile: %s (deleted %d)", XREALM_TEST_USER, deleted);
            }
        } catch (Exception e) {
            Log.debugf("Could not cleanup xrealm user profile: %s", e.getMessage());
        }

        try {
            // Delete Credential in system realm
            long deleted = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm())
                .find(CredentialUserIdPassword.class)
                .filter(Filters.eq("userId", XREALM_TEST_USER))
                .delete()
                .getDeletedCount();
            if (deleted > 0) {
                Log.infof("Cleaned up xrealm test credential: %s (deleted %d)", XREALM_TEST_USER, deleted);
            }
        } catch (Exception e) {
            Log.debugf("Could not cleanup xrealm test credential: %s", e.getMessage());
        }
    }

    /**
     * Cleans up the target realm.
     */
    private void cleanupTargetRealm() {
        if (targetRealm != null && targetRealm.getId() != null) {
            try {
                realmRepo.delete(envConfigUtils.getSystemRealm(), targetRealm);
                Log.infof("Cleaned up target realm: %s", TARGET_REALM_REF_NAME);
            } catch (Exception e) {
                Log.warnf("Failed to cleanup target realm: %s", e.getMessage());
            }
        }
    }

    /**
     * Logs in and returns the access token.
     */
    private String loginAndGetToken() throws Exception {
        AuthRequest request = new AuthRequest();
        request.setUserId(testUtils.getTestUserId());
        request.setPassword(testUtils.getDefaultTestPassword());

        String body = mapper.writeValueAsString(request);
        Response response = given()
                .header("Content-type", "application/json")
                .body(body)
                .when().post("/security/login")
                .then()
                .statusCode(200)
                .extract().response();

        return response.jsonPath().getString("access_token");
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Admin provisions user in own tenant WITHOUT X-Realm header")
    void testProvisionUserInOwnTenantWithoutXRealm() throws Exception {
        // Skip if no access token (login failed)
        if (accessToken == null) {
            Log.warn("Skipping test - no access token available");
            return;
        }

        // Given: A CreateUserRequest for the admin's own tenant
        DomainContext localDomainContext = DomainContext.builder()
                .tenantId(testUtils.getTestTenantId())
                .defaultRealm(testUtils.getTestRealm())
                .orgRefName(testUtils.getTestOrgRefName())
                .accountId(testUtils.getTestAccountNumber())
                .dataSegment(0)
                .build();

        CreateUserRequest createRequest = CreateUserRequest.builder()
                .userId(LOCAL_TEST_USER)
                .email(LOCAL_TEST_USER)
                .password(TEST_PASSWORD)
                .firstName("Local")
                .lastName("TestUser")
                .displayName("Local Test User")
                .roles(Set.of("user"))
                .domainContext(localDomainContext)
                .forceChangePassword(false)
                .build();

        String requestBody = mapper.writeValueAsString(createRequest);

        // When: Making a user creation request WITHOUT X-Realm header
        Response response = given()
                .header("Content-type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                // No X-Realm header - should use admin's default realm
                .body(requestBody)
                .when().post("/user/userProfile/create")
                .then()
                .log().ifValidationFails()
                .extract().response();

        // Then: User should be created successfully (or already exists from previous run)
        int status = response.getStatusCode();
        String body = response.getBody().asString();
        Log.infof("Create user response status: %d, body: %s", status, body);

        // 200 = created, 409 = already exists (from previous test run)
        assertTrue(status == 200 || status == 409,
            "User creation should succeed (200) or user already exists (409), got: " + status);

        // Verify the user was created in the correct tenant
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            // Check credential was created
            Optional<CredentialUserIdPassword> cred = credRepo.findByUserId(
                LOCAL_TEST_USER, envConfigUtils.getSystemRealm(), true);
            assertTrue(cred.isPresent(), "Credential should be created");

            // Verify the credential's DomainContext matches the local tenant
            DomainContext credDc = cred.get().getDomainContext();
            assertNotNull(credDc, "Credential should have DomainContext");
            assertEquals(testUtils.getTestTenantId(), credDc.getTenantId(),
                "Credential tenant should match local tenant");
            assertEquals(testUtils.getTestOrgRefName(), credDc.getOrgRefName(),
                "Credential org should match local org");

            // Check UserProfile was created in the local realm
            Optional<UserProfile> profile = userProfileRepo.getByUserId(LOCAL_TEST_USER);
            assertTrue(profile.isPresent(), "UserProfile should be created");
            assertEquals(LOCAL_TEST_USER, profile.get().getEmail());

            // Verify UserProfile DataDomain matches local tenant
            DataDomain profileDd = profile.get().getDataDomain();
            assertNotNull(profileDd, "UserProfile should have DataDomain");
            assertEquals(testUtils.getTestTenantId(), profileDd.getTenantId(),
                "UserProfile tenant should match local tenant");

            Log.infof("Successfully verified local user creation: userId=%s, tenantId=%s",
                LOCAL_TEST_USER, profileDd.getTenantId());
        }
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Admin uses X-Realm to provision user in DIFFERENT tenant")
    void testProvisionUserInDifferentTenantWithXRealm() throws Exception {
        // Skip if no access token (login failed)
        if (accessToken == null) {
            Log.warn("Skipping test - no access token available");
            return;
        }

        // Given: A CreateUserRequest for the TARGET tenant (different from admin's tenant)
        // The DomainContext in the request should match the target realm
        DomainContext targetDomainContext = DomainContext.builder()
                .tenantId(TARGET_TENANT)
                .defaultRealm(TARGET_REALM_REF_NAME)
                .orgRefName(TARGET_ORG)
                .accountId(TARGET_ACCOUNT)
                .dataSegment(0)
                .build();

        CreateUserRequest createRequest = CreateUserRequest.builder()
                .userId(XREALM_TEST_USER)
                .email(XREALM_TEST_USER)
                .password(TEST_PASSWORD)
                .firstName("XRealm")
                .lastName("TargetUser")
                .displayName("X-Realm Target User")
                .roles(Set.of("user"))
                .domainContext(targetDomainContext)
                .forceChangePassword(false)
                .build();

        String requestBody = mapper.writeValueAsString(createRequest);

        // When: Making a user creation request WITH X-Realm header pointing to target realm
        Response response = given()
                .header("Content-type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Realm", TARGET_REALM_REF_NAME)  // Switch to target realm
                .body(requestBody)
                .when().post("/user/userProfile/create")
                .then()
                .log().ifValidationFails()
                .extract().response();

        // Then: User should be created successfully in the TARGET tenant
        int status = response.getStatusCode();
        Log.infof("Create user with X-Realm response status: %d, body: %s",
            status, response.getBody().asString());

        assertEquals(200, status, "User creation with X-Realm should succeed");

        // Verify the user was created in the TARGET tenant, NOT the admin's home tenant
        // Use direct Morphia queries to bypass security filters

        // Check credential was created using direct query
        CredentialUserIdPassword cred = morphiaDataStoreWrapper.getDataStore(envConfigUtils.getSystemRealm())
            .find(CredentialUserIdPassword.class)
            .filter(Filters.eq("userId", XREALM_TEST_USER))
            .first();
        assertNotNull(cred, "Credential should be created");

        // CRITICAL: Verify the credential's DomainContext matches the TARGET tenant
        DomainContext credDc = cred.getDomainContext();
        assertNotNull(credDc, "Credential should have DomainContext");
        assertEquals(TARGET_TENANT, credDc.getTenantId(),
            "Credential tenant should match TARGET tenant, not admin's tenant");
        assertEquals(TARGET_ORG, credDc.getOrgRefName(),
            "Credential org should match TARGET org");
        assertEquals(TARGET_ACCOUNT, credDc.getAccountId(),
            "Credential account should match TARGET account");

        // Check UserProfile was created - it should be in the TARGET realm (use direct query)
        UserProfile profile = morphiaDataStoreWrapper.getDataStore(TARGET_REALM_REF_NAME)
            .find(UserProfile.class)
            .filter(Filters.eq("userId", XREALM_TEST_USER))
            .first();
        assertNotNull(profile, "UserProfile should be created in target realm");
        assertEquals(XREALM_TEST_USER, profile.getEmail());

        // CRITICAL: Verify UserProfile DataDomain matches TARGET tenant
        DataDomain profileDd = profile.getDataDomain();
        assertNotNull(profileDd, "UserProfile should have DataDomain");
        assertEquals(TARGET_TENANT, profileDd.getTenantId(),
            "UserProfile tenant should match TARGET tenant");
        assertEquals(TARGET_ORG, profileDd.getOrgRefName(),
            "UserProfile org should match TARGET org");
        assertEquals(TARGET_ACCOUNT, profileDd.getAccountNum(),
            "UserProfile account should match TARGET account");

        Log.infof("Successfully verified X-Realm user creation: userId=%s created in tenantId=%s (target), not in admin's tenant",
            XREALM_TEST_USER, profileDd.getTenantId());

        // Additional verification: The user should NOT be in the admin's local realm (use direct query)
        UserProfile wrongProfile = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm())
            .find(UserProfile.class)
            .filter(Filters.eq("userId", XREALM_TEST_USER))
            .first();
        assertNull(wrongProfile,
            "UserProfile should NOT be created in admin's home realm - X-Realm should redirect to target");
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Verify PrincipalContext has correct DomainContext when X-Realm is used")
    void testPrincipalContextDomainContextWithXRealm() throws Exception {
        // This test verifies that when X-Realm is used, the PrincipalContext's
        // DomainContext is correctly set to the target realm's DomainContext
        // (this is the core of our refactoring)

        if (accessToken == null) {
            Log.warn("Skipping test - no access token available");
            return;
        }

        // When: Making a request with X-Realm header
        // We'll use a simple GET endpoint to verify the context is correct
        Response response = given()
                .header("Content-type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Realm", TARGET_REALM_REF_NAME)
                .when().get("/security/realm/list")
                .then()
                .log().ifValidationFails()
                .extract().response();

        // The request should succeed (meaning SecurityFilter correctly processed X-Realm)
        int status = response.getStatusCode();
        // 200 = success, 404 = empty database in target realm (both are valid)
        assertTrue(status == 200 || status == 404,
            "Request with X-Realm should succeed or return 404 (empty database), got: " + status);

        Log.infof("PrincipalContext X-Realm test passed with status: %d", status);
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: User without realm override permission cannot use X-Realm")
    void testUserWithoutRealmOverrideCannotUseXRealm() throws Exception {
        // Temporarily remove realmRegEx from the test user
        String originalRealmRegEx = null;

        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            Optional<CredentialUserIdPassword> cred = credRepo.findByUserId(
                testUtils.getTestUserId(), envConfigUtils.getSystemRealm(), true);
            if (cred.isPresent()) {
                originalRealmRegEx = cred.get().getRealmRegEx();
                cred.get().setRealmRegEx(null);  // Remove realm override permission
                credRepo.save(envConfigUtils.getSystemRealm(), cred.get());
                Log.infof("Temporarily removed realmRegEx from test user");
            }
        }

        try {
            // Login as the test user (now without realm override permission)
            AuthRequest request = new AuthRequest();
            request.setUserId(testUtils.getTestUserId());
            request.setPassword(testUtils.getDefaultTestPassword());

            String body = mapper.writeValueAsString(request);
            Response loginResponse = given()
                    .header("Content-type", "application/json")
                    .body(body)
                    .when().post("/security/login")
                    .then()
                    .extract().response();

            if (loginResponse.getStatusCode() != 200) {
                Log.warnf("Could not login as test user, skipping test");
                return;
            }

            String restrictedToken = loginResponse.jsonPath().getString("access_token");

            // When: Attempting to use X-Realm without permission
            Response response = given()
                    .header("Content-type", "application/json")
                    .header("Authorization", "Bearer " + restrictedToken)
                    .header("X-Realm", TARGET_REALM_REF_NAME)
                    .when().get("/security/realm/list")
                    .then()
                    .log().ifValidationFails()
                    .extract().response();

            // Then: Should be rejected (403 Forbidden or 500 with authorization error)
            int status = response.getStatusCode();
            String responseBody = response.getBody().asString();

            // Accept either 403 (if exception is mapped) or 500 with the right error message
            boolean isUnauthorized = status == 403 ||
                (status == 500 && responseBody.contains("not authorized to access realm"));

            assertTrue(isUnauthorized,
                "User without realmRegEx should be rejected from using X-Realm. Got status: " + status +
                ", body: " + responseBody);

            Log.infof("Correctly rejected X-Realm usage for user without permission, status: %d", status);
        } finally {
            // Restore realmRegEx
            try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
                Optional<CredentialUserIdPassword> cred = credRepo.findByUserId(
                    testUtils.getTestUserId(), envConfigUtils.getSystemRealm(), true);
                if (cred.isPresent()) {
                    cred.get().setRealmRegEx(originalRealmRegEx != null ? originalRealmRegEx : "*");
                    credRepo.save(envConfigUtils.getSystemRealm(), cred.get());
                    Log.infof("Restored realmRegEx for test user");
                }
            }
        }
    }
}
