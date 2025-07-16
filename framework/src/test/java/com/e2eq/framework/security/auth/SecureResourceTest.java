package com.e2eq.framework.security.auth;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.AuthProviderFactory;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;

@QuarkusTest
public class SecureResourceTest {
    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    AuthProviderFactory authFactory;

    @Inject
    TestUtils testUtils;

    @Inject
    RuleContext ruleContext;

    @Test
    public void testSecuredEndpoints() throws ReferentialIntegrityViolationException {
        AuthProvider.LoginResponse loginResponse = null;

        String[] roles = {"admin", "user"};
        PrincipalContext pContext = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        ResourceContext rContext = testUtils.getResourceContext(testUtils.getArea(), "userProfile", "update");
        testUtils.initDefaultRules(ruleContext, "security","userProfile", testUtils.getTestUserId());
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            authFactory.getUserManager().removeUserWithUsername(testUtils.getTestRealm(), "testuser@end2endlogic.com");
            if (authProvider.equals("custom")) {
                // Create test user with roles
                if (!authFactory.getUserManager().usernameExists(testUtils.getTestRealm(), "testuser@end2endlogic.com")) {

                    authFactory.getUserManager().createUser(testUtils.getTestRealm(), "testuser@end2endlogic.com", "P@55w@rd", "testuser@end2endlogic.com", Set.of("user"), testUtils.getTestDomainContext());
                    authFactory.getUserManager().enableRealmOverrideWithUsername("testuser@end2endlogic.com", testUtils.getTestRealm(), "*");

                } else {
                    Log.info("User already exists, skipping creation");
                }
                loginResponse = authFactory.getAuthProvider().login(testUtils.getTestRealm(), "testuser@end2endlogic.com", "P@55w@rd");
            } else {
                loginResponse = authFactory.getAuthProvider().login(testUtils.getTestRealm(), "system@system.com", "test123456");
            }
        }


        if (loginResponse.authenticated() && (loginResponse.positiveResponse().roles().contains("user") || loginResponse.positiveResponse().roles().contains("admin"))) {
            given()
                    .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
                    .header("X-Realm", testUtils.getTestRealm())
                    .when()
                    .get("/secure/authenticated")
                    .then()
                    .statusCode(200);

            // Test user endpoint
            given()
                    .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
               .header("X-Realm", testUtils.getTestRealm())
                    .when()
                    .get("/secure/view")
                    .then()
                    .statusCode(200);
        }

        if (loginResponse.authenticated() && (!loginResponse.positiveResponse().roles().contains("admin"))) {
            // Test admin endpoint (should fail)
            given()
                    .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
                    .header("X-Realm", testUtils.getTestRealm())
                    .when()
                    .post("/secure/create")
                    .then()
                    .statusCode(403);
        }
    }

    @Test
    public void testUnauthenticatedAccess() {
        given()
        .when()
            .get("/secure/authenticated")
        .then()
            .statusCode(401);
    }

    @Test
    public void testImpersonation() throws ReferentialIntegrityViolationException {
    // first ensure that the there are two users in the system one that is a admin the other that is just a normal us
        String[] roles = {"admin", "user"};
        PrincipalContext pContext = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        ResourceContext rContext = testUtils.getResourceContext(testUtils.getArea(), "userProfile", "update");
        testUtils.initDefaultRules(ruleContext, "security","userProfile", testUtils.getTestUserId());
        AuthProvider.LoginResponse loginResponse;
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {

            if (authFactory.getUserManager().userIdExists(testUtils.getTestRealm(), "testuser@end2endlogic.com")) {
                authFactory.getUserManager().removeUserWithUserId(testUtils.getTestRealm(),"testuser@end2endlogic.com");
            }

            authFactory.getUserManager().createUser(testUtils.getTestRealm(), "testuser@end2endlogic.com", "P@55w@rd", "testuser@end2endlogic.com", Set.of("user"), testUtils.getTestDomainContext());

            if (authFactory.getUserManager().userIdExists(testUtils.getTestRealm(),"testadmin@end2endlogic.com")) {
                authFactory.getUserManager().removeUserWithUserId(testUtils.getTestRealm(),"testadmin@end2endlogic.com");
            }

            authFactory.getUserManager().createUser(testUtils.getTestRealm(), "testadmin@end2endlogic.com", "P@55w@rd", "testadmin@end2endlogic.com", Set.of("admin"), testUtils.getTestDomainContext());
            authFactory.getUserManager().enableImpersonationWithUserId("testadmin@end2endlogic.com", "true", "*", testUtils.getTestRealm());

           loginResponse = authFactory.getAuthProvider().login(testUtils.getTestRealm(), "testadmin@end2endlogic.com", "P@55w@rd");
            Assertions.assertTrue(loginResponse.authenticated());
        }

    Response response = given()
            .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
            .header("X-Impersonate-UserId", "testuser@end2endlogic.com")
            .header("X-Realm",testUtils.getTestRealm())
            .when()
            .get("/security/authenticated/test")
            .then()
           .extract().response();

        System.out.println("Response: " + response.asString());
        Assertions.assertEquals(200, response.getStatusCode());
    }
}
