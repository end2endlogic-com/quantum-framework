package com.e2eq.framework.security.auth;

import com.e2eq.framework.model.security.auth.AuthProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;


@QuarkusTest
public class AuthProviderTest {

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Test
    public void testAdminLogin() {
        if (authProvider.equals("cognito")) {

         AuthProvider.LoginResponse response = given()
                 .contentType(ContentType.JSON)
                 .queryParam("userId", "testadmin@end2endlogic.com")
                 .queryParam("password", "P@55w@rd")
                 //.body("{\"username\": \"admin\", \"password\": \"adminpass\"}")
                 .when()
                 .post("/auth/login")
                 .then()
                 .statusCode(Response.Status.OK.getStatusCode())
                 .body("accessToken", notNullValue())
                 .body("refreshToken", notNullValue())
                 .extract()
                 .as(AuthProvider.LoginResponse.class);

         // Test admin access
         given()
                 .header("Authorization", "Bearer " + response.accessToken())
                 .when()
                 .post("/secure/create")
                 .then()
                 .statusCode(200)
                 .body("message", equalTo("Secure content created"));
        }
    }

    @Test
    public void testUserLogin() {
        if (authProvider.equals("cognito")) {
            AuthProvider.LoginResponse response = given()
                    .contentType(ContentType.JSON)
                    .queryParam("userId", "testuser@end2endlogic.com")
                    .queryParam("password", "P@55w@rd")
                    .when()
                    .post("/auth/login")
                    .then()
                    .statusCode(200)
                    .body("accessToken", notNullValue())
                    .body("refreshToken", notNullValue())
                    .extract()
                    .as(AuthProvider.LoginResponse.class);

            // Test user access to view
            given()
                    .header("Authorization", "Bearer " + response.accessToken())
                    .when()
                    .get("/secure/view")
                    .then()
                    .statusCode(200)
                    .body("message", equalTo("Secure content viewed"));

            // Test user cannot access admin endpoint
            given()
                    .header("Authorization", "Bearer " + response.accessToken())
                    .when()
                    .post("/secure/create")
                    .then()
                    .statusCode(403);
        }
    }

    @Test
    public void testPublicAccess() {
        given()
                .when()
                .get("/secure/public")
                .then()
                .statusCode(200)
                .body("message", equalTo("Public content"));
    }

    @Test
    public void testInvalidToken() {
        given()
                .header("Authorization", "Bearer invalid-token")
                .when()
                .get("/secure/view")
                .then()
                .statusCode(401);
    }
}

