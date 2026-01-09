package com.e2eq.framework.security.annotations;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.model.securityrules.SecurityContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the bypassDataScoping attribute on @FunctionalAction.
 * 
 * <p>These tests verify that:
 * <ul>
 *   <li>The bypassDataScoping attribute is correctly read from the annotation</li>
 *   <li>System-level operations with bypassDataScoping=true succeed even with SCOPED constraints</li>
 *   <li>Normal operations without bypassDataScoping have constraints enforced at data layer</li>
 * </ul>
 */
@QuarkusTest
public class BypassDataScopingTest {

    @AfterEach
    void clearContexts() {
        SecurityContext.clear();
    }

    /**
     * Verifies that the bypassDataScoping attribute defaults to false.
     */
    @Test
    public void testBypassDataScopingDefaultsFalse() throws NoSuchMethodException {
        Method method = BypassDataScopingTestResource.class.getMethod("normalOperation");
        FunctionalAction fa = method.getAnnotation(FunctionalAction.class);
        
        assertNotNull(fa, "@FunctionalAction annotation should be present");
        assertFalse(fa.bypassDataScoping(), "bypassDataScoping should default to false");
        assertEquals("NORMAL_OP", fa.value());
    }

    /**
     * Verifies that bypassDataScoping=true is correctly read from the annotation.
     */
    @Test
    public void testBypassDataScopingTrue() throws NoSuchMethodException {
        Method method = BypassDataScopingTestResource.class.getMethod("systemOperation");
        FunctionalAction fa = method.getAnnotation(FunctionalAction.class);
        
        assertNotNull(fa, "@FunctionalAction annotation should be present");
        assertTrue(fa.bypassDataScoping(), "bypassDataScoping should be true");
        assertEquals("SYSTEM_OP", fa.value());
    }

    /**
     * Tests that an endpoint with bypassDataScoping=true can be accessed by admin.
     * The SCOPED constraints from the tenant admin rule should be bypassed.
     */
    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin"})
    public void testSystemOperationWithBypassDataScoping() {
        RestAssured.given()
                .when()
                .post("/annotated-bypass/system-operation")
                .then()
                .statusCode(200)
                .body(containsStringIgnoringCase("bypass:system:admin:SYSTEM_OP"));
    }

    /**
     * Tests that a normal operation endpoint can be accessed by admin.
     * SCOPED constraints may be present but are handled at the data layer.
     */
    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin"})
    public void testNormalOperationWithoutBypass() {
        RestAssured.given()
                .when()
                .post("/annotated-bypass/normal-operation")
                .then()
                .statusCode(200)
                .body(containsStringIgnoringCase("normal:system:admin:NORMAL_OP"));
    }

    /**
     * Tests that an endpoint with inferred action (no @FunctionalAction) works.
     */
    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin"})
    public void testInferredActionWithoutAnnotation() {
        RestAssured.given()
                .when()
                .get("/annotated-bypass/inferred-action")
                .then()
                .statusCode(200)
                .body(containsStringIgnoringCase("inferred:system:admin:VIEW"));
    }

    /**
     * Tests that unauthorized users are denied access regardless of bypassDataScoping.
     */
    @Test
    @TestSecurity(user = "regular@example.com", roles = {"user"})
    public void testBypassDataScopingStillRequiresAuthorization() {
        // Users without admin role should be denied
        RestAssured.given()
                .when()
                .post("/annotated-bypass/system-operation")
                .then()
                .statusCode(403);
    }
}

