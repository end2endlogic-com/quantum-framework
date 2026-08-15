package com.e2eq.framework.rest.ratelimit;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(RateLimitEnforceQuarkusTest.EnforceProfile.class)
class RateLimitEnforceQuarkusTest {

    @Test
    void providerIsDiscoveredAndReturnsTheDocumentedTyped429() {
        given()
                .when().get("/q/openapi?format=json")
                .then()
                .statusCode(200)
                .body("components.schemas.QuantumRateLimitError.properties.keySet().size()", equalTo(4))
                .body("paths.'/__quantum-rate-limit-test'.get.responses.'429'.headers.'Retry-After'", notNullValue())
                .body("paths.'/__quantum-rate-limit-test'.get.responses.'429'.content.'application/json'.schema.'$ref'",
                        equalTo("#/components/schemas/QuantumRateLimitError"));

        given()
                .when().get("/__quantum-rate-limit-test")
                .then()
                .statusCode(200)
                .body(equalTo("ok"));

        given()
                .when().get("/__quantum-rate-limit-test")
                .then()
                .statusCode(429)
                .header("Retry-After", "60")
                .contentType("application/json;charset=UTF-8")
                .body("status", equalTo(429))
                .body("statusMessage", equalTo("Too Many Requests"));
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    RateLimitConfig.MODE, "ENFORCE",
                    RateLimitConfig.REQUEST_LIMIT, "1",
                    RateLimitConfig.REFILL_SECONDS, "60",
                    RateLimitConfig.MAX_TRACKED_CLIENTS, "100",
                    RateLimitConfig.IDLE_SECONDS, "600");
        }
    }
}
