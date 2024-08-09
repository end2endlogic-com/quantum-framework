package com.e2eq.framework.api.security;


import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
public class SecurityAPITests {
    @Test
    public void bestLineTest() {
        given()
                .when().get("/hello")
                .then()
                .statusCode(200)
                .body(is("Hello RESTEasy"));
    }

    @Test
    public void testUserProfileAPI() throws Exception {
        given()
              .when().get("/user/userProfile/list")
              .then()
              .statusCode(401);
    }

    @Test
    public void testAuthentication() {

    }


}
