package com.e2eq.framework.system;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Phase B / wp3 tier-1 contract: setting quantum.system-rest.enabled=false at
 * build time removes the entire control-plane admin REST surface in one
 * switch — replacing the fragile per-type quarkus.arc.exclude-types list.
 * Domain endpoints and the rest of the framework remain untouched.
 */
@QuarkusTest
@TestProfile(TestSystemRestDisabled.SystemRestDisabledProfile.class)
@TestSecurity(user = "sysAdmin@system-com", roles = {"admin"})
public class TestSystemRestDisabled {

    public static class SystemRestDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quantum.system-rest.enabled", "false");
        }
    }

    @Test
    public void adminSurfaceIsGone() {
        given().when().get("/security/realm").then().statusCode(404);
        given().when().get("/security/realm-memberships").then().statusCode(404);
        given().when().get("/system/migration/dbversion/some-realm").then().statusCode(404);
        given().when().get("/admin/tenants/runs/some-ref").then().statusCode(404);
        given().when().get("/admin/seeds/pending/some-realm").then().statusCode(404);
        given().when().get("/admin/bootstrap-packs/pending/some-realm").then().statusCode(404);
        given().when().get("/onboarding/workflow/current").then().statusCode(404);
        given().when().get("/admin/tenants/workflow/current").then().statusCode(404);
    }

    @Test
    public void frameworkStillServesNonAdminEndpoints() {
        // The switch must be surgical: health (and the rest of the app) stay up.
        given().when().get("/q/health").then().statusCode(200);
    }
}
