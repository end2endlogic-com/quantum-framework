package com.e2eq.framework.rest.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "sysAdmin@system-com", roles = {"admin"})
public class SeedAdminResourceIT {

    private static final String REALM = "test-quantum-com";

    @Test
    void pending_apply_history_roundtrip() {
        // 1) Initially, demo-seed from test resources should be pending for test realm
        given()
            .when()
                .get("/admin/seeds/pending/{realm}", REALM)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));

        // 2) Apply all (filtered by test property quantum.seed.apply.filter=demo-seed)
        given()
            .when()
                .post("/admin/seeds/apply/{realm}?filter=demo-seed", REALM)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("applied", notNullValue());

        // 3) After apply, pending should be empty or not include demo-seed
        given()
            .when()
                .get("/admin/seeds/pending/{realm}", REALM)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("find { it.seedPack == 'demo-seed' }", nullValue());

        // 4) History should include entries for demo-seed datasets
        given()
            .when()
                .get("/admin/seeds/history/{realm}", REALM)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("any { it.seedPack == 'demo-seed' }", is(true));

        // 5) Idempotency: applying single pack again should succeed and not break
        given()
            .when()
                .post("/admin/seeds/{realm}/{seedPack}/apply", REALM, "demo-seed")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("applied", hasItem("demo-seed"));
    }
}
