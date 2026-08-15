package com.e2eq.framework.rest.ratelimit;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class RateLimitOffQuarkusTest {

    @Test
    void defaultOffModeBootsAndLeavesRequestsUntouched() {
        given()
                .when().get("/__quantum-rate-limit-test")
                .then()
                .statusCode(200)
                .body(equalTo("ok"));

        given()
                .when().get("/q/openapi?format=json")
                .then()
                .statusCode(200)
                .body("components.schemas.QuantumRateLimitError", nullValue())
                .body("paths.'/__quantum-rate-limit-test'.get.responses.'429'", nullValue());
    }
}
