package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.UserProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class QueryGatewayResourceIT {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    private String realm;

    @BeforeEach
    public void setUp() {
        // Determine test realm; do not rely on data presence
        realm = System.getProperty("quantum.realm.testRealm", "test-quantum-com");
        // Ensure datastore can be obtained (will create if needed)
        morphiaDataStoreWrapper.getDataStore(realm);
    }

    @Test
    public void plan_endpoint_reports_mode() {
        Map<String,Object> body = new HashMap<>();
        body.put("rootType", UserProfile.class.getName());
        body.put("query", "status:active");
        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/plan")
        .then()
            .statusCode(200)
            .body("mode", is("FILTER"))
            .body("expandPaths", hasSize(0));

        body.put("query", "expand(customer) && status:active");
        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/plan")
        .then()
            .statusCode(200)
            .body("mode", is("AGGREGATION"))
            .body("expandPaths", hasSize(1))
            .body("expandPaths[0]", is("customer"));
    }

    @Test
    public void find_endpoint_executes_filter_mode_and_501_for_aggregation() {
        Map<String,Object> body = new HashMap<>();
        body.put("rootType", UserProfile.class.getName());
        body.put("query", "displayName:*Alice*");
        Map<String,Object> page = new HashMap<>();
        page.put("limit", 10);
        page.put("skip", 0);
        body.put("page", page);
        body.put("realm", realm);

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/find")
        .then()
            .statusCode(200)
            .body("rows", notNullValue())
            .body("offset", is(0))
            .body("limit", is(10))
            .body("filter", is("displayName:*Alice*"));

        // Aggregation with unresolved target collection should return 422 (since feature flag is on in tests)
        body.put("query", "expand(customer) && status:active");
        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/find")
        .then()
            .statusCode(422)
            .body("error", is("UnresolvedCollection"));
    }
}
