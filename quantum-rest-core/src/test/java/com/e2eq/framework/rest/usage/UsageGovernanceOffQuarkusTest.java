package com.e2eq.framework.rest.usage;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class UsageGovernanceOffQuarkusTest {

    @Test
    void defaultOffModeIsInertAndDoesNotAdvertiseEnforcementResponses() {
        given()
                .when().get("/__quantum-usage-test")
                .then()
                .statusCode(200)
                .body(equalTo("ok"));

        given()
                .when().get("/q/openapi?format=json")
                .then()
                .statusCode(200)
                .body("components.schemas.QuantumUsageGovernanceError", nullValue())
                .body("paths.'/__quantum-usage-test'.get.responses.'429'", nullValue())
                .body("paths.'/__quantum-usage-test'.get.responses.'503'", nullValue());
    }
}
