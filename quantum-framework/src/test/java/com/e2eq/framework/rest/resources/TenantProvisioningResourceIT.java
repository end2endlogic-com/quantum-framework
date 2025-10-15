package com.e2eq.framework.rest.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "sysAdmin@system-com", roles = {"admin"})
public class TenantProvisioningResourceIT {

    @Test
    void provision_with_archetype_applies_seed_packs() {
        // Prepare request to provision a new tenant with DemoArchetype
        Map<String, Object> body = Map.of(
                "tenantEmailDomain", "demo-archetype.example",
                "orgRefName", "demo-archetype.example",
                "accountId", "9999999999",
                "adminUserId", "admin@demo-archetype.example",
                "adminUsername", "admin@demo-archetype.example",
                "adminPassword", "secret",
                "archetypes", List.of("DemoArchetype")
        );

        // 1) Provision
        String realm = given()
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/admin/tenants")
            .then()
                .statusCode(anyOf(is(200), is(201)))
                .contentType(ContentType.JSON)
                .body("realmId", equalTo("demo-archetype-example"))
                .extract()
                .jsonPath().getString("realmId");

        // 2) Verify that history for the new realm includes demo-seed entries (archetype includes demo-seed)
        given()
            .when()
                .get("/admin/seeds/history/{realm}", realm)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("any { it.seedPack == 'demo-seed' }", is(true));

        // 3) Pending should not include demo-seed immediately after apply
        given()
            .when()
                .get("/admin/seeds/pending/{realm}", realm)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("find { it.seedPack == 'demo-seed' }", nullValue());
    }
}
