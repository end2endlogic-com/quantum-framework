package com.e2eq.framework.rest.resources;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;

@QuarkusTest
public class PermissionResourceTest {
    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    AuthProviderFactory authFactory;

    @Inject
    TestUtils testUtils;

    @Inject
    RuleContext ruleContext;

    private static final String TEST_USER_ID = "testuser-permissions@end2endlogic.com";
    private static final String TEST_PASSWORD = "P@55w@rd";
    private AuthProvider.LoginResponse loginResponse;

    @BeforeEach
    void setUp() throws ReferentialIntegrityViolationException {
        // Setup security context for user creation
        String[] roles = {"admin", "user"};
        PrincipalContext pContext = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        ResourceContext rContext = testUtils.getResourceContext("SYSTEM", "PERMISSIONS", "VIEW");
        ruleContext.initDefaultRules("SYSTEM", "PERMISSIONS", testUtils.getTestUserId());
        
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            // Clean up existing test users
            if (authFactory.getUserManager().userIdExists(TEST_USER_ID)) {
                authFactory.getUserManager().removeUserWithUserId(TEST_USER_ID);
            }

            if (authProvider.equals("custom")) {
                // Create test user with only "user" role (not admin)
                authFactory.getUserManager().createUser(
                    TEST_USER_ID, 
                    TEST_PASSWORD, 
                    Set.of("user"),  // Only "user" role, no "admin"
                    testUtils.getTestDomainContext()
                );
                authFactory.getUserManager().enableRealmOverrideWithUserId(TEST_USER_ID, "*");
                
                // Login as test user
                loginResponse = authFactory.getAuthProvider().login(TEST_USER_ID, TEST_PASSWORD);
            } else {
                // Fallback for non-custom auth provider
                loginResponse = authFactory.getAuthProvider().login("system@system.com", "test123456");
            }
        }
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void testCheckEndpointWithOwnIdentity() {
        if (!loginResponse.authenticated()) {
            Log.warn("Skipping test - user not authenticated");
            return;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("identity", TEST_USER_ID);
        requestBody.put("area", "SYSTEM");
        requestBody.put("functionalDomain", "PERMISSIONS");
        requestBody.put("action", "VIEW");
        requestBody.put("realm", testUtils.getTestRealm());
        requestBody.put("orgRefName", testUtils.getTestOrgRefName());
        requestBody.put("accountNumber", testUtils.getTestAccountNumber());
        requestBody.put("tenantId", testUtils.getTestTenantId());

        Response response = given()
            .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
            .header("X-Realm", testUtils.getTestRealm())
            .contentType(ContentType.JSON)
            .body(requestBody)
            .when()
            .post("/system/permissions/check")
            .then()
            .extract().response();

        Assertions.assertEquals(200, response.getStatusCode(), "Should return 200 when checking own identity");
        Assertions.assertNotNull(response.getBody().asString());
    }

    @Test
    void testEvaluateEndpointWithOwnIdentity() {
        if (!loginResponse.authenticated()) {
            Log.warn("Skipping test - user not authenticated");
            return;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("identity", TEST_USER_ID);
        requestBody.put("realm", testUtils.getTestRealm());

        Response response = given()
            .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
            .header("X-Realm", testUtils.getTestRealm())
            .contentType(ContentType.JSON)
            .body(requestBody)
            .when()
            .post("/system/permissions/fd/evaluate")
            .then()
            .extract().response();

        Assertions.assertEquals(200, response.getStatusCode(), "Should return 200 when evaluating own identity");
        Assertions.assertNotNull(response.getBody().asString());
    }

    @Test
    void testCheckWithIndexEndpoint() {
        if (!loginResponse.authenticated()) {
            Log.warn("Skipping test - user not authenticated");
            return;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("identity", TEST_USER_ID);
        requestBody.put("realm", testUtils.getTestRealm());

        Response response = given()
            .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
            .header("X-Realm", testUtils.getTestRealm())
            .contentType(ContentType.JSON)
            .body(requestBody)
            .when()
            .post("/system/permissions/check-with-index")
            .then()
            .extract().response();

        Assertions.assertEquals(200, response.getStatusCode(), "Should return 200 when checking index with own identity");
        Assertions.assertNotNull(response.getBody().asString());
    }

    @Test
    void testRoleProvenanceEndpoint() {
        if (!loginResponse.authenticated()) {
            Log.warn("Skipping test - user not authenticated");
            return;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", TEST_USER_ID);
        requestBody.put("realm", testUtils.getTestRealm());

        Response response = given()
            .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
            .header("X-Realm", testUtils.getTestRealm())
            .contentType(ContentType.JSON)
            .body(requestBody)
            .when()
            .post("/system/permissions/role-provenance")
            .then()
            .extract().response();

        Assertions.assertEquals(200, response.getStatusCode(), "Should return 200 for role provenance");
        Assertions.assertNotNull(response.getBody().asString());
    }
}

