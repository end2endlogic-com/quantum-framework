package com.e2eq.framework.security.annotations;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.is;

import com.e2eq.framework.model.securityrules.SecurityContext;

@QuarkusTest
public class FunctionalAnnotationsTest {

    @AfterEach
    void clearContexts() {
        // Defensive cleanup; SecurityFilter also clears on response
        SecurityContext.clear();
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin"})
    public void testFunctionalMappingAndActionAnnotations() {
        RestAssured.given()
                .when().get("/annotated/view")
                .then()
                .statusCode(200)
                .body(is("sales:order:view"));
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin"})
    public void testFunctionalMappingWithHttpMethodInference() {
        RestAssured.given()
                .when().post("/annotated/create")
                .then()
                .statusCode(200)
                .body(is("sales:order:create"));
    }
}
