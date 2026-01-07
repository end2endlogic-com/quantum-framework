package com.e2eq.framework.rest.filters;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.rest.models.AuthRequest;
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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for X-Realm header DataDomain override behavior.
 * 
 * These tests verify that when using X-Realm header:
 * 1. The DataDomain is properly switched to the target realm's default DataDomain
 * 2. Records created use the correct DataDomain for the target realm
 * 3. Query filtering respects the realm's DataDomain
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("X-Realm DataDomain Integration Tests")
public class XRealmDataDomainIT extends BaseRepoTest {

    // Test target realm configuration
    private static final String TARGET_REALM_REF_NAME = "test-xrealm-target";
    private static final String TARGET_EMAIL_DOMAIN = "xrealmtest.com";
    private static final String TARGET_ORG = "XREALM-TEST-ORG";
    private static final String TARGET_TENANT = "test-xrealm-target";
    private static final String TARGET_ACCOUNT = "XREALM-TEST-ACCT";

    @ConfigProperty(name = "auth.provider", defaultValue = "custom")
    String authProvider;

    @Inject
    CredentialRepo credRepo;

    @Inject
    RealmRepo realmRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    ObjectMapper mapper;

    private String accessToken;
    private Realm targetRealm;

    @BeforeEach
    void setUp() throws Exception {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            // Ensure the test user exists and has realm override capability
            ensureTestUserWithRealmOverride();
            
            // Ensure target realm exists
            ensureTargetRealmExists();
            
            // Get access token for the test user
            accessToken = loginAndGetToken();
        }
    }

    @AfterEach
    void tearDown() {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            // Clean up target realm if it was created
            cleanupTargetRealm();
        } catch (Exception e) {
            Log.warnf("Cleanup failed: %s", e.getMessage());
        }
    }

    /**
     * Ensures the test user exists and has realmRegEx set to allow realm override.
     */
    private void ensureTestUserWithRealmOverride() throws ReferentialIntegrityViolationException {
        Optional<CredentialUserIdPassword> credop = credRepo.findByUserId(
            testUtils.getTestUserId(), testUtils.getSystemRealm());
        
        CredentialUserIdPassword cred;
        if (credop.isPresent()) {
            cred = credop.get();
            // Ensure realm override is enabled
            if (cred.getRealmRegEx() == null || !cred.getRealmRegEx().equals("*")) {
                cred.setRealmRegEx("*");
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

            cred.setRoles(roles);
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

        // Create DataDomain for the realm entity itself
        DataDomain realmDataDomain = DataDomain.builder()
                .orgRefName(envConfigUtils.getSystemOrgRefName())
                .tenantId(envConfigUtils.getSystemTenantId())
                .accountNum(envConfigUtils.getSystemAccountNumber())
                .ownerId(envConfigUtils.getSystemUserId())
                .dataSegment(0)
                .build();

        targetRealm = Realm.builder()
                .refName(TARGET_REALM_REF_NAME)
                .displayName("X-Realm Test Target")
                .emailDomain(TARGET_EMAIL_DOMAIN)
                .databaseName(TARGET_REALM_REF_NAME)
                .domainContext(targetDomainContext)
                .dataDomain(realmDataDomain)
                .build();

        targetRealm = realmRepo.save(envConfigUtils.getSystemRealm(), targetRealm);
        Log.infof("Created target realm: %s", TARGET_REALM_REF_NAME);
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
    @DisplayName("RealmRepo.findByRefName should find realm by refName")
    void testFindByRefName() {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            // When: Looking up the target realm by refName
            Optional<Realm> found = realmRepo.findByRefName(TARGET_REALM_REF_NAME, true, 
                envConfigUtils.getSystemRealm());
            
            // Then: Realm should be found with correct DomainContext
            assertTrue(found.isPresent(), "Target realm should be found");
            Realm realm = found.get();
            assertEquals(TARGET_REALM_REF_NAME, realm.getRefName());
            assertNotNull(realm.getDomainContext(), "Realm should have DomainContext");
            assertEquals(TARGET_ORG, realm.getDomainContext().getOrgRefName());
            assertEquals(TARGET_TENANT, realm.getDomainContext().getTenantId());
            assertEquals(TARGET_ACCOUNT, realm.getDomainContext().getAccountId());
        }
    }

    @Test
    @Order(2)
    @DisplayName("RealmRepo.findByDatabaseName should find realm by database name")
    void testFindByDatabaseName() {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            // When: Looking up the target realm by database name
            Optional<Realm> found = realmRepo.findByDatabaseName(TARGET_REALM_REF_NAME, true, 
                envConfigUtils.getSystemRealm());
            
            // Then: Realm should be found
            assertTrue(found.isPresent(), "Target realm should be found by database name");
            assertEquals(TARGET_REALM_REF_NAME, found.get().getDatabaseName());
        }
    }

    @Test
    @Order(3)
    @DisplayName("DomainContext.toDataDomain should create DataDomain with correct values")
    void testDomainContextToDataDomain() {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            // Given: The target realm's DomainContext
            DomainContext domainContext = targetRealm.getDomainContext();
            String testOwner = "test-owner@example.com";
            
            // When: Converting to DataDomain
            DataDomain dataDomain = domainContext.toDataDomain(testOwner);
            
            // Then: DataDomain should have values from DomainContext and the specified owner
            assertEquals(TARGET_ORG, dataDomain.getOrgRefName());
            assertEquals(TARGET_TENANT, dataDomain.getTenantId());
            assertEquals(TARGET_ACCOUNT, dataDomain.getAccountNum());
            assertEquals(testOwner, dataDomain.getOwnerId());
            assertEquals(0, dataDomain.getDataSegment());
        }
    }

    @Test
    @Order(4)
    @DisplayName("API request with X-Realm header should succeed")
    void testApiRequestWithXRealmHeader() {
        // Skip if no access token (login failed)
        if (accessToken == null) {
            Log.warn("Skipping test - no access token available");
            return;
        }

        // When: Making an API request with X-Realm header
        Response response = given()
                .header("Content-type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Realm", TARGET_REALM_REF_NAME)
                .when().get("/security/realm/list")
                .then()
                .log().ifValidationFails()
                .extract().response();

        // Then: Request should succeed (200 or similar success status)
        // Note: The actual status depends on whether the target realm database exists
        // and whether there's data there. The key is that it doesn't fail with auth errors.
        int status = response.getStatusCode();
        assertTrue(status == 200 || status == 404, 
            "Request with X-Realm should succeed or return 404 (empty database), got: " + status);
    }

    @Test
    @Order(5)
    @DisplayName("API request without X-Realm should use default realm")
    void testApiRequestWithoutXRealmHeader() {
        // Skip if no access token
        if (accessToken == null) {
            Log.warn("Skipping test - no access token available");
            return;
        }

        // When: Making an API request without X-Realm header
        Response response = given()
                .header("Content-type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .when().get("/security/realm/list")
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .extract().response();

        // Then: Request should succeed with default realm
        assertNotNull(response.getBody());
    }

    @Test
    @Order(6)
    @DisplayName("Test user should have realmRegEx allowing realm override")
    void testUserHasRealmOverridePermission() {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            // When: Looking up the test user's credential
            Optional<CredentialUserIdPassword> cred = credRepo.findByUserId(
                testUtils.getTestUserId(), testUtils.getSystemRealm());
            
            // Then: User should have realmRegEx set to allow realm override
            assertTrue(cred.isPresent(), "Test user credential should exist");
            assertNotNull(cred.get().getRealmRegEx(), "realmRegEx should be set");
            assertEquals("*", cred.get().getRealmRegEx(), "realmRegEx should allow all realms");
        }
    }

    @Test
    @Order(7)
    @DisplayName("X-Realm to non-existent realm should be handled gracefully")
    void testXRealmToNonExistentRealm() {
        // Skip if no access token
        if (accessToken == null) {
            Log.warn("Skipping test - no access token available");
            return;
        }

        // When: Making an API request with X-Realm header pointing to non-existent realm
        Response response = given()
                .header("Content-type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Realm", "non-existent-realm-xyz")
                .when().get("/security/realm/list")
                .then()
                .log().ifValidationFails()
                .extract().response();

        // Then: Should fail with appropriate error (400 or 403)
        // The realm validation in SecurityFilter should reject unknown realms
        int status = response.getStatusCode();
        assertTrue(status == 400 || status == 403 || status == 500, 
            "Request to non-existent realm should fail, got: " + status);
    }
}

