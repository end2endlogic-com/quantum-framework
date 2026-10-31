package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "system@system.com", roles = {"admin"})
public class TenantProvisioningResourceIT {

    @Inject UserRealmRoleRepo userRealmRoleRepo;
    @Inject EnvConfigUtils envConfigUtils;

    @Test
    void provision_with_archetype_applies_seed_packs() {
        String domain = "archetype-" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".example";
        String adminUser = "admin@" + domain;
        // Prepare request to provision a new tenant with DemoArchetype
        Map<String, Object> body = Map.of(
                "tenantEmailDomain", domain,
                "applicationId", "quantum-framework-test",
                "orgRefName", domain,
                "accountId", "9999999999",
                "adminUserId", adminUser,
                "adminUsername", adminUser,
                "adminPassword", "secret",
                "archetypes", List.of("DemoArchetype")
        );

        // 1) Provision
        io.restassured.path.json.JsonPath response = given()
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/admin/tenants")
            .then()
                .statusCode(anyOf(is(200), is(201)))
                .contentType(ContentType.JSON)
                .body("realmId", equalTo("quantum-framework-test-D-" + domain.replace('.', '-')))
                .body("executionRef", notNullValue())
                .body("status", equalTo("COMPLETED"))
                .extract()
                .jsonPath();

        String realm = response.getString("realmId");
        String executionRef = response.getString("executionRef");

        assertThat(
            userRealmRoleRepo.findActiveRolesForRealmWithIgnoreRules(
                adminUser,
                realm,
                envConfigUtils.getSystemRealm()
            ),
            containsInAnyOrder("admin", "user")
        );

        given().header("X-Realm", realm)
            .when()
                .get("/admin/tenants/runs/{executionRef}", executionRef)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("executionRef", equalTo(executionRef))
                .body("realmId", equalTo(realm))
                .body("status", equalTo("COMPLETED"));

        // 2) Verify that history for the new realm includes demo-seed entries (archetype includes demo-seed)
        given().header("X-Realm", realm)
            .when()
                .get("/admin/seeds/history/{realm}", realm)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("any { it.seedPack == 'demo-seed' }", is(true));

        // 3) Pending should not include demo-seed immediately after apply
        given().header("X-Realm", realm)
            .when()
                .get("/admin/seeds/pending/{realm}", realm)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("find { it.seedPack == 'demo-seed' }", nullValue());
    }
}
