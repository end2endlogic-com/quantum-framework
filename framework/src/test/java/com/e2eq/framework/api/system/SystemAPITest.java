package com.e2eq.framework.api.system;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
public class SystemAPITest {
    @Test
    public void testMapping() {
            given()
                    .when().get("/system/mapping")
                    .prettyPeek()
                    .then()
                    .statusCode(200);
    }
}
