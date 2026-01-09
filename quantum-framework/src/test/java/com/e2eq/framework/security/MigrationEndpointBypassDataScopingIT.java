package com.e2eq.framework.security;

import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the migration endpoint with bypassDataScoping=true.
 * 
 * <p>This test verifies that the applyAllIndexes endpoint works correctly
 * even when the permission check returns SCOPED constraints that cannot
 * be applied to system-level operations.</p>
 */
@QuarkusTest
public class MigrationEndpointBypassDataScopingIT extends BaseRepoTest {

    @Inject
    TestUtils testUtils;

    @AfterEach
    void clearContexts() {
        SecurityContext.clear();
    }

    /**
     * Test that the applyAllIndexes endpoint works for admin users.
     * The bypassDataScoping=true should allow the request to proceed
     * even though SCOPED constraints are returned.
     */
    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin"})
    public void testApplyAllIndexesEndpoint() {
        String realm = testUtils.getTestRealm();
        
        Response response = RestAssured.given()
                .header("X-Realm", realm)
                .when()
                .post("/system/migration/indexes/applyAllIndexes/" + realm)
                .then()
                .extract()
                .response();
        
        System.out.println("=== applyAllIndexes Response ===");
        System.out.println("Status Code: " + response.getStatusCode());
        System.out.println("Body: " + response.asString());
        System.out.println("Headers: " + response.getHeaders());
        
        // Should succeed with 200 or 204 (void return)
        assertTrue(response.getStatusCode() >= 200 && response.getStatusCode() < 300, 
                "Expected success status code but got: " + response.getStatusCode() + 
                ", body: " + response.asString());
    }

    /**
     * Test that the applyIndexes endpoint works for admin users.
     */
    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin"})
    public void testApplyIndexesEndpoint() {
        String realm = testUtils.getTestRealm();
        
        Response response = RestAssured.given()
                .header("X-Realm", realm)
                .when()
                .post("/system/migration/indexes/applyIndexes/" + realm)
                .then()
                .extract()
                .response();
        
        System.out.println("=== applyIndexes Response ===");
        System.out.println("Status Code: " + response.getStatusCode());
        System.out.println("Body: " + response.asString());
        
        assertTrue(response.getStatusCode() >= 200 && response.getStatusCode() < 300,
                "Expected success status code but got: " + response.getStatusCode() +
                ", body: " + response.asString());
    }

    /**
     * Test that non-admin users are denied access regardless of bypassDataScoping.
     */
    @Test
    @TestSecurity(user = "regular@example.com", roles = {"user"})
    public void testApplyAllIndexesDeniedForNonAdmin() {
        String realm = testUtils.getTestRealm();
        
        Response response = RestAssured.given()
                .header("X-Realm", realm)
                .when()
                .post("/system/migration/indexes/applyAllIndexes/" + realm)
                .then()
                .extract()
                .response();
        
        System.out.println("=== Non-Admin applyAllIndexes Response ===");
        System.out.println("Status Code: " + response.getStatusCode());
        System.out.println("Body: " + response.asString());
        
        // Should be denied with 403
        assertEquals(403, response.getStatusCode(), "Non-admin should be denied access");
    }

    /**
     * Test the permission check API for migration:indexes:apply_all_indexes.
     * This verifies what the check API returns for this combination.
     */
    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin"})
    public void testPermissionCheckForMigration() {
        String checkPayload = """
            {
                "identity": "system@system.com",
                "area": "MIGRATION",
                "functionalDomain": "INDEXES",
                "action": "APPLY_ALL_INDEXES"
            }
            """;
        
        Response response = RestAssured.given()
                .header("Content-Type", "application/json")
                .body(checkPayload)
                .when()
                .post("/system/permissions/check")
                .then()
                .extract()
                .response();
        
        System.out.println("=== Permission Check Response ===");
        System.out.println("Status Code: " + response.getStatusCode());
        System.out.println("Body: " + response.prettyPrint());
        
        assertEquals(200, response.getStatusCode());
        
        // Check the decision
        String decision = response.jsonPath().getString("decision");
        String finalEffect = response.jsonPath().getString("finalEffect");
        String decisionScope = response.jsonPath().getString("decisionScope");
        
        System.out.println("Decision: " + decision);
        System.out.println("FinalEffect: " + finalEffect);
        System.out.println("DecisionScope: " + decisionScope);
        
        assertEquals("ALLOW", decision, "Admin should be allowed");
    }
}

