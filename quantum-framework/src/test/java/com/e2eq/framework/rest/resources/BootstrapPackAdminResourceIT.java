package com.e2eq.framework.rest.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestSecurity(user = "sysAdmin@system-com", roles = {"admin"})
public class BootstrapPackAdminResourceIT {

    private static final String REALM = "test-quantum-com";

    @Test
    void pending_validate_apply_history_roundtrip() {
        given()
                .when()
                .get("/admin/bootstrap-packs")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("packRef", hasItem("demo-bootstrap-a"))
                .body("packRef", hasItem("demo-bootstrap-b"));

        given()
                .when()
                .get("/admin/bootstrap-packs/pending/{realm}", REALM)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("packRef", hasItem("demo-bootstrap-a"))
                .body("packRef", hasItem("demo-bootstrap-b"));

        given()
                .when()
                .post("/admin/bootstrap-packs/validate/{realm}?filter=demo-bootstrap-b", REALM)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("applied", hasItem("demo-bootstrap-b"))
                .body("runs", hasSize(1))
                .body("runs[0].mode", equalTo("VALIDATE_ONLY"))
                .body("runs[0].status", equalTo("COMPLETED"));

        given()
                .when()
                .post("/admin/bootstrap-packs/apply/{realm}?filter=demo-bootstrap-b", REALM)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("applied", hasItem("demo-bootstrap-b"))
                .body("runs", hasSize(1))
                .body("runs[0].packRef", equalTo("demo-bootstrap-b"))
                .body("runs[0].mode", equalTo("APPLY_MISSING"));

        given()
                .when()
                .get("/admin/bootstrap-packs/pending/{realm}", REALM)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("find { it.packRef == 'demo-bootstrap-b' }", nullValue())
                .body("find { it.packRef == 'demo-bootstrap-a' }.packRef", equalTo("demo-bootstrap-a"));

        given()
                .when()
                .get("/admin/bootstrap-packs/history/{realm}?filter=demo-bootstrap-b", REALM)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(2))
                .body("[0].packRef", equalTo("demo-bootstrap-b"));

        given()
                .when()
                .post("/admin/bootstrap-packs/{realm}/{packRef}/apply?mode=reapply", REALM, "demo-bootstrap-b")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("packRef", equalTo("demo-bootstrap-b"))
                .body("mode", equalTo("REAPPLY"))
                .body("status", equalTo("COMPLETED"))
                .body("steps", notNullValue());
    }
}
