package com.e2eq.framework.rest.usage;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.AttributeType;
import io.quarkus.test.security.SecurityAttribute;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(UsageGovernanceEnforceQuarkusTest.EnforceProfile.class)
class UsageGovernanceEnforceQuarkusTest {

    @Inject
    UsageGovernanceTestObserver observer;

    @Test
    void enforceFailsClosedWithoutTrustedPostAuthenticationIdentity() {
        given()
                .when().get("/__quantum-usage-test")
                .then()
                .statusCode(503)
                .body("code", equalTo(UsageEnforcementStateException.PRINCIPAL_IDENTITY_UNAVAILABLE));
    }

    @Test
    void enforceLeavesPermitAllEndpointAvailableWithoutAnIdentity() {
        given()
                .when().get("/__quantum-usage-test/public")
                .then()
                .statusCode(200)
                .body(equalTo("public"));
    }

    @Test
    @TestSecurity(
            user = "subject-a",
            roles = "user",
            attributes = @SecurityAttribute(
                    key = "tenantId",
                    value = "tenant-a",
                    type = AttributeType.STRING))
    void authenticatedRequestsExerciseCdiObservationAndReal429Serialization() {
        observer.reset();

        given()
                .when().get("/__quantum-usage-test")
                .then()
                .statusCode(200)
                .body(equalTo("ok"));

        given()
                .when().get("/__quantum-usage-test")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .contentType("application/json;charset=UTF-8")
                .body("code", equalTo("USAGE_LIMIT_EXCEEDED"))
                .body("endpointId", equalTo("system:usage_test:quantumUsageTest"));

        assertEquals(2, observer.observations().size());
        assertEquals(UsageAdmissionDisposition.ADMITTED,
                observer.observations().get(0).admission().disposition());
        assertEquals(UsageAdmissionDisposition.REJECTED,
                observer.observations().get(1).admission().disposition());
        assertEquals("tenant-a", observer.observations().get(1).tenantId());
        assertEquals("subject-a", observer.observations().get(1).subjectId());
    }

    @Test
    @TestSecurity(
            user = "class-secured-subject",
            roles = "user",
            attributes = @SecurityAttribute(
                    key = "tenantId",
                    value = "tenant-a",
                    type = AttributeType.STRING))
    void methodAuthenticationOverridesClassLevelPermitAllForGovernance() {
        given()
                .when().get("/__quantum-usage-class-public-test/authenticated")
                .then()
                .statusCode(200)
                .body(equalTo("authenticated"));

        given()
                .when().get("/__quantum-usage-class-public-test/authenticated")
                .then()
                .statusCode(429)
                .body("code", equalTo("USAGE_LIMIT_EXCEEDED"));
    }

    @Test
    void enforcePublishesTypedOpenApiResponses() {
        given()
                .when().get("/q/openapi?format=json")
                .then()
                .statusCode(200)
                .body("components.schemas.QuantumUsageGovernanceError", notNullValue())
                .body("paths.'/__quantum-usage-test'.get.responses.'429'", notNullValue())
                .body("paths.'/__quantum-usage-test'.get.responses.'503'", notNullValue());
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    UsageGovernanceConfig.MODE, "ENFORCE",
                    UsageGovernanceConfig.POLICY_REQUEST_LIMIT, "1",
                    UsageGovernanceConfig.POLICY_REFILL_PERIOD, "PT1M",
                    UsageGovernanceConfig.POLICY_ENDPOINTS, "*");
        }
    }
}
