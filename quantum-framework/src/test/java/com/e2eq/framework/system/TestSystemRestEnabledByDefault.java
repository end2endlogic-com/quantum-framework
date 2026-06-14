package com.e2eq.framework.system;

import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * wp3 no-change guarantee for the admin REST surface: with nothing configured,
 * the control-plane admin resources register exactly as before
 * (quantum.system-rest.enabled defaults to true via enableIfMissing).
 */
@QuarkusTest
@TestSecurity(user = "sysAdmin@system-com", roles = {"admin"})
public class TestSystemRestEnabledByDefault {

    @Inject
    EnvConfigUtils envConfigUtils;

    @Test
    public void adminSurfaceIsRegisteredByDefault() {
        // Registered endpoints must not 404 (any other status means the
        // resource exists and normal request processing applied).
        int realmStatus = given().when().get("/security/realm").statusCode();
        Assertions.assertNotEquals(404, realmStatus,
            "/security/realm must be registered by default");

        // Probe with the configured (existing, migrated) system realm — an
        // unknown realm gets a domain-level 404 from this endpoint by design.
        int migrationStatus = given().when()
            .get("/system/migration/dbversion/" + envConfigUtils.getSystemRealm()).statusCode();
        Assertions.assertNotEquals(404, migrationStatus,
            "/system/migration must be registered by default");
    }
}
