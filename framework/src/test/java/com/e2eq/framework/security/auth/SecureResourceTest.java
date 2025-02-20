package com.e2eq.framework.security.auth;

import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.AuthProviderFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;

@QuarkusTest
public class SecureResourceTest {
    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    AuthProviderFactory authFactory;

    @Test
    public void testSecuredEndpoints() {
        if (authProvider.equals("cognito")) {
            // Create test user with roles
            if (!authFactory.getUserManager().userExists("testuser@end2endlogic.com")) {
                authFactory.getUserManager().createUser("testuser@end2endlogic.com", "P@55w@rd", Set.of("user"));
            }
            AuthProvider.LoginResponse loginResponse = authFactory.getAuthProvider().login("testuser@end2endlogic.com", "P@55w@rd");

            // Test authenticated endpoint
            given()
                    .header("Authorization", "Bearer " + loginResponse.accessToken())
                    .when()
                    .get("/secure/authenticated")
                    .then()
                    .statusCode(200);

            // Test user endpoint
            given()
                    .header("Authorization", "Bearer " + loginResponse.accessToken())
                    .when()
                    .get("/secure/user")
                    .then()
                    .statusCode(200);

            // Test admin endpoint (should fail)
            given()
                    .header("Authorization", "Bearer " + loginResponse.accessToken())
                    .when()
                    .get("/secure/admin")
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
}
