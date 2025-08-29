package com.e2eq.framework.api.system;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
public class HierarchicalResourceTest {

  @Test
  public void testTree() {
      given()
              .when().get("/system/menuItemHierarchy/trees")
              .prettyPeek()
              .then()
              .statusCode(200);
  }
}
