package com.e2eq.framework.security.auth;

import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.AuthProviderFactory;
import io.quarkus.logging.Log;
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
        AuthProvider.LoginResponse loginResponse = null;
        if (authProvider.equals("cognito")) {
            // Create test user with roles
            if (!authFactory.getUserManager().usernameExists("testuser@end2endlogic.com")) {
                authFactory.getUserManager().createUser("testuser@end2endlogic.com", "P@55w@rd", "testuser@end2endlogic.com", Set.of("user"), null);
            } else {
                Log.info("User already exists, skipping creation");
            }
            loginResponse = authFactory.getAuthProvider().login("testuser@end2endlogic.com", "P@55w@rd");
        } else {
            loginResponse = authFactory.getAuthProvider().login("system@system.com", "test123456");
        }


        if (loginResponse.authenticated() && (loginResponse.positiveResponse().roles().contains("user") || loginResponse.positiveResponse().roles().contains("admin"))) {
            given()
                    .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
                    .when()
                    .get("/secure/authenticated")
                    .then()
                    .statusCode(200);

            // Test user endpoint
            given()
                    .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
                    .when()
                    .get("/secure/view")
                    .then()
                    .statusCode(200);
        }

        if (loginResponse.authenticated() && (!loginResponse.positiveResponse().roles().contains("admin"))) {
            // Test admin endpoint (should fail)
            given()
                    .header("Authorization", "Bearer " + loginResponse.positiveResponse().accessToken())
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
}
