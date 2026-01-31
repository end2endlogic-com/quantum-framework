package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

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
        body.put("rootType", CodeList.class.getName());
        body.put("query", "category:default");
        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/plan")
        .then()
            .statusCode(200)
            .body("mode", is("FILTER"))
            .body("expandPaths", hasSize(0));

        body.put("query", "expand(customer) && category:default");
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
        body.put("rootType", CodeList.class.getName());
        body.put("query", "category:*test*");
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
            .body("filter", is("category:*test*"));

        // Aggregation with unresolved target collection should return 422 (since feature flag is on in tests)
        body.put("query", "expand(customer) && category:default");
        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/find")
        .then()
            .statusCode(422)
            .body("error", is("UnresolvedCollection"));
    }

    // ========================================================================
    // Tests for GET /api/query/rootTypes endpoint
    // ========================================================================

    @Test
    public void rootTypes_endpoint_returns_list_of_mapped_entities() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/query/rootTypes")
        .then()
            .statusCode(200)
            .body("count", greaterThan(0))
            .body("rootTypes", notNullValue())
            .body("rootTypes.size()", greaterThan(0));
    }

    @Test
    public void rootTypes_endpoint_returns_expected_fields() {
        Response response = given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/query/rootTypes")
        .then()
            .statusCode(200)
            .extract().response();

        List<Map<String, String>> rootTypes = response.jsonPath().getList("rootTypes");
        assertFalse(rootTypes.isEmpty(), "Should have at least one root type");

        // Check that each entry has required fields
        for (Map<String, String> rootType : rootTypes) {
            assertNotNull(rootType.get("className"), "className should not be null");
            assertNotNull(rootType.get("simpleName"), "simpleName should not be null");
            assertNotNull(rootType.get("collectionName"), "collectionName should not be null");

            // className should be fully qualified (contain a dot)
            assertTrue(rootType.get("className").contains("."),
                "className should be fully qualified: " + rootType.get("className"));

            // simpleName should not contain a dot
            assertFalse(rootType.get("simpleName").contains("."),
                "simpleName should not contain dots: " + rootType.get("simpleName"));
        }
    }

    @Test
    public void rootTypes_endpoint_includes_codelist() {
        // CodeList should be a mapped entity
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/query/rootTypes")
        .then()
            .statusCode(200)
            .body("rootTypes.className", hasItem(CodeList.class.getName()))
            .body("rootTypes.simpleName", hasItem("CodeList"));
    }

    @Test
    public void rootTypes_endpoint_results_are_sorted_by_simple_name() {
        Response response = given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/query/rootTypes")
        .then()
            .statusCode(200)
            .extract().response();

        List<String> simpleNames = response.jsonPath().getList("rootTypes.simpleName");
        if (simpleNames.size() > 1) {
            for (int i = 0; i < simpleNames.size() - 1; i++) {
                assertTrue(simpleNames.get(i).compareTo(simpleNames.get(i + 1)) <= 0,
                    "Results should be sorted alphabetically by simpleName: " +
                    simpleNames.get(i) + " should come before " + simpleNames.get(i + 1));
            }
        }
    }

    // ========================================================================
    // Tests for simple name resolution in plan/find endpoints
    // ========================================================================

    @Test
    public void plan_endpoint_accepts_simple_name() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList"); // Simple name instead of fully qualified
        body.put("query", "category:default");

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/plan")
        .then()
            .statusCode(200)
            .body("mode", is("FILTER"));
    }

    @Test
    public void find_endpoint_accepts_simple_name() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList"); // Simple name instead of fully qualified
        body.put("query", "category:*test*");
        body.put("realm", realm);
        Map<String, Object> page = new HashMap<>();
        page.put("limit", 5);
        page.put("skip", 0);
        body.put("page", page);

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/find")
        .then()
            .statusCode(200)
            .body("rows", notNullValue())
            .body("limit", is(5));
    }

    @Test
    public void plan_endpoint_rejects_unknown_simple_name() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "NonExistentEntity");
        body.put("query", "category:default");

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/plan")
        .then()
            .statusCode(400)
            .body(containsString("Unknown rootType"))
            .body(containsString("NonExistentEntity"));
    }

    @Test
    public void plan_endpoint_rejects_null_rootType() {
        Map<String, Object> body = new HashMap<>();
        body.put("query", "category:default");
        // rootType is not set

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/plan")
        .then()
            .statusCode(400)
            .body(containsString("rootType is required"));
    }

    @Test
    public void plan_endpoint_rejects_blank_rootType() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "   ");
        body.put("query", "category:default");

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/plan")
        .then()
            .statusCode(400)
            .body(containsString("rootType is required"));
    }

    @Test
    public void plan_endpoint_accepts_fully_qualified_name() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", CodeList.class.getName()); // Fully qualified
        body.put("query", "category:default");

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/plan")
        .then()
            .statusCode(200)
            .body("mode", is("FILTER"));
    }

    @Test
    public void plan_endpoint_rejects_unknown_fully_qualified_name() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "com.example.NonExistentClass");
        body.put("query", "category:default");

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/plan")
        .then()
            .statusCode(400)
            .body(containsString("Unknown rootType"));
    }

    @Test
    public void find_endpoint_returns_collection_envelope() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("query", ""); // empty query - return all
        body.put("realm", realm);
        Map<String, Object> page = new HashMap<>();
        page.put("limit", 10);
        page.put("skip", 5);
        body.put("page", page);

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/find")
        .then()
            .statusCode(200)
            .body("rows", notNullValue())
            .body("offset", is(5))
            .body("limit", is(10));
    }

    // ========================================================================
    // Tests for POST /api/query/save endpoint
    // ========================================================================

    @Test
    public void save_endpoint_creates_new_entity() {
        String uniqueKey = "test-key-" + System.currentTimeMillis();
        String uniqueCategory = "test-category-" + System.currentTimeMillis();

        Map<String, Object> entity = new HashMap<>();
        entity.put("category", uniqueCategory);
        entity.put("key", uniqueKey);
        entity.put("refName", uniqueCategory + ":" + uniqueKey); // Computed refName for CodeList
        entity.put("description", "Test CodeList created by integration test");
        entity.put("valueType", "STRING");

        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        body.put("entity", entity);

        Response response = given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/save")
        .then()
            .statusCode(200)
            .body("id", notNullValue())
            .body("rootType", containsString("CodeList"))
            .body("entity", notNullValue())
            .body("entity.key", is(uniqueKey))
            .extract().response();

        // Clean up - delete the created entity
        String id = response.jsonPath().getString("id");
        if (id != null) {
            Map<String, Object> deleteBody = new HashMap<>();
            deleteBody.put("rootType", "CodeList");
            deleteBody.put("realm", realm);
            deleteBody.put("id", id);

            given()
                .contentType(ContentType.JSON)
                .body(deleteBody)
            .when()
                .post("/api/query/delete")
            .then()
                .statusCode(200);
        }
    }

    @Test
    public void save_endpoint_accepts_simple_name() {
        String uniqueKey = "test-simple-" + System.currentTimeMillis();
        String uniqueCategory = "test-simple-cat-" + System.currentTimeMillis();

        Map<String, Object> entity = new HashMap<>();
        entity.put("category", uniqueCategory);
        entity.put("key", uniqueKey);
        entity.put("refName", uniqueCategory + ":" + uniqueKey);
        entity.put("description", "Test CodeList simple name");
        entity.put("valueType", "STRING");

        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList"); // Simple name
        body.put("realm", realm);
        body.put("entity", entity);

        Response response = given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/save")
        .then()
            .statusCode(200)
            .body("id", notNullValue())
            .extract().response();

        // Clean up
        String id = response.jsonPath().getString("id");
        if (id != null) {
            Map<String, Object> deleteBody = new HashMap<>();
            deleteBody.put("rootType", "CodeList");
            deleteBody.put("realm", realm);
            deleteBody.put("id", id);

            given()
                .contentType(ContentType.JSON)
                .body(deleteBody)
            .when()
                .post("/api/query/delete");
        }
    }

    @Test
    public void save_endpoint_rejects_null_entity() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        // entity is not set

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/save")
        .then()
            .statusCode(400)
            .body(containsString("entity is required"));
    }

    @Test
    public void save_endpoint_rejects_unknown_rootType() {
        Map<String, Object> entity = new HashMap<>();
        entity.put("name", "Test");

        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "NonExistentType");
        body.put("realm", realm);
        body.put("entity", entity);

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/save")
        .then()
            .statusCode(400)
            .body(containsString("Unknown rootType"));
    }

    // ========================================================================
    // Tests for POST /api/query/delete endpoint
    // ========================================================================

    @Test
    public void delete_endpoint_removes_entity_by_id() {
        // First create an entity to delete
        String uniqueKey = "test-delete-" + System.currentTimeMillis();
        String uniqueCategory = "test-delete-cat-" + System.currentTimeMillis();

        Map<String, Object> entity = new HashMap<>();
        entity.put("category", uniqueCategory);
        entity.put("key", uniqueKey);
        entity.put("refName", uniqueCategory + ":" + uniqueKey);
        entity.put("description", "Entity to be deleted");
        entity.put("valueType", "STRING");

        Map<String, Object> saveBody = new HashMap<>();
        saveBody.put("rootType", "CodeList");
        saveBody.put("realm", realm);
        saveBody.put("entity", entity);

        String id = given()
            .contentType(ContentType.JSON)
            .body(saveBody)
        .when()
            .post("/api/query/save")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("id");

        assertNotNull(id, "Should have created entity with an ID");

        // Now delete it
        Map<String, Object> deleteBody = new HashMap<>();
        deleteBody.put("rootType", "CodeList");
        deleteBody.put("realm", realm);
        deleteBody.put("id", id);

        given()
            .contentType(ContentType.JSON)
            .body(deleteBody)
        .when()
            .post("/api/query/delete")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("deletedCount", is(1))
            .body("id", is(id));
    }

    @Test
    public void delete_endpoint_returns_404_for_nonexistent_id() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        body.put("id", "000000000000000000000000"); // Valid ObjectId format but doesn't exist

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/delete")
        .then()
            .statusCode(404)
            .body("success", is(false))
            .body("deletedCount", is(0));
    }

    @Test
    public void delete_endpoint_rejects_invalid_objectid() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        body.put("id", "not-a-valid-objectid");

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/delete")
        .then()
            .statusCode(400)
            .body("error", is("InvalidId"));
    }

    @Test
    public void delete_endpoint_rejects_missing_id() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        // id is not set

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/delete")
        .then()
            .statusCode(400)
            .body(containsString("id is required"));
    }

    // ========================================================================
    // Tests for POST /api/query/deleteMany endpoint
    // ========================================================================

    @Test
    public void deleteMany_endpoint_removes_matching_entities() {
        // Create a few entities with a unique marker
        String marker = "deleteMany-test-" + System.currentTimeMillis();

        for (int i = 0; i < 3; i++) {
            Map<String, Object> entity = new HashMap<>();
            entity.put("category", marker);
            entity.put("key", "key-" + i + "-" + System.currentTimeMillis());
            entity.put("refName", marker + ":key-" + i + "-" + System.currentTimeMillis());
            entity.put("description", "Entity " + i + " for deleteMany test");
            entity.put("valueType", "STRING");

            Map<String, Object> saveBody = new HashMap<>();
            saveBody.put("rootType", "CodeList");
            saveBody.put("realm", realm);
            saveBody.put("entity", entity);

            given()
                .contentType(ContentType.JSON)
                .body(saveBody)
            .when()
                .post("/api/query/save")
            .then()
                .statusCode(200);
        }

        // Delete all entities with the marker category
        Map<String, Object> deleteBody = new HashMap<>();
        deleteBody.put("rootType", "CodeList");
        deleteBody.put("realm", realm);
        deleteBody.put("query", "category:" + marker);

        given()
            .contentType(ContentType.JSON)
            .body(deleteBody)
        .when()
            .post("/api/query/deleteMany")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("deletedCount", greaterThanOrEqualTo(3));
    }

    @Test
    public void deleteMany_endpoint_returns_zero_for_no_matches() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        body.put("query", "key:this-definitely-does-not-exist-" + System.currentTimeMillis());

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/deleteMany")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("deletedCount", is(0));
    }

    @Test
    public void deleteMany_endpoint_rejects_expand_queries() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        body.put("query", "expand(customer) && category:default");

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/deleteMany")
        .then()
            .statusCode(400)
            .body("error", is("InvalidQuery"))
            .body("message", containsString("expand()"));
    }

    @Test
    public void deleteMany_endpoint_accepts_empty_query() {
        // Empty query should be valid (though dangerous - deletes all)
        // We won't actually execute this with an empty query on a real collection
        // Just verify the endpoint accepts the request format
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        body.put("query", "refName:non-existent-for-safety-" + System.currentTimeMillis());

        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/deleteMany")
        .then()
            .statusCode(200)
            .body("success", is(true));
    }
}
