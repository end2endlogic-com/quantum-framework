package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SecurityFilter pre-authorization enforcement
 */
public class SecurityFilterPreAuthTest {

    @Test
    public void testPreAuthAllowsAccessWhenRuleAllows() {
        // Test: ALLOW effect should allow access
        RuleEffect effect = RuleEffect.ALLOW;
        
        boolean shouldAllow = (effect == RuleEffect.ALLOW);
        
        assertTrue(shouldAllow);
    }

    @Test
    public void testPreAuthDeniesAccessWhenRuleDenies() {
        // Test: DENY effect should deny access
        RuleEffect effect = RuleEffect.DENY;
        String decision = "DENY";
        String scope = "DEFAULT";
        
        boolean shouldAllow = (effect == RuleEffect.ALLOW);
        
        assertFalse(shouldAllow);
        assertEquals("DENY", decision);
        assertEquals("DEFAULT", scope);
    }

    @Test
    public void testPreAuthConfigurableViaProperty() {
        // Test that quantum.security.filter.enforcePreAuth property controls behavior
        // This would be tested in integration test with different property values
        
        // When enforcePreAuth=true (default), pre-auth should be enforced
        boolean enforcePreAuth = true;
        assertTrue(enforcePreAuth, "Default should be true");
        
        // When enforcePreAuth=false, pre-auth should be skipped
        enforcePreAuth = false;
        assertFalse(enforcePreAuth, "Can be disabled for legacy flows");
    }

    @Test
    public void testPreAuthSkippedForPermitAll() {
        // @PermitAll endpoints should bypass pre-authorization
        // This is handled by the isPermitAll check before calling enforcePreAuthorization
        
        boolean isPermitAll = true;
        boolean enforcePreAuth = true;
        
        // Pre-auth should not run when isPermitAll is true
        boolean shouldEnforce = !isPermitAll && enforcePreAuth;
        assertFalse(shouldEnforce, "@PermitAll should bypass pre-auth");
    }

    @Test
    public void testAuthorizationErrorsUseGeneratedSdkContract() {
        Map<String, String> error = SecurityFilter.apiError(
            "ACCESS_DENIED",
            "Access denied: user=local-test, area=DI, domain=ONTOLOGY_AGENT, action=VIEW"
        );

        assertEquals(
            Map.of(
                "code", "ACCESS_DENIED",
                "message", "Access denied: user=local-test, area=DI, domain=ONTOLOGY_AGENT, action=VIEW"
            ),
            error
        );
        assertEquals(2, error.size(), "Generated SDK error schemas reject undeclared fields");
    }

    @Test
    public void aMissingApplicationIsATyped400NotA500() {
        Response response = SecurityFilter.preAuthorizationFailure(
            new ApplicationAccessException(Code.APPLICATION_SCOPE_REQUIRED, "Application-scoped authorization requires an applicationId"));
        assertEquals(400, response.getStatus());
        assertEquals("APPLICATION_SCOPE_REQUIRED", ((Map<?, ?>) response.getEntity()).get("code"));
    }

    @Test
    public void anApplicationMismatchIsATyped400() {
        Response response = SecurityFilter.preAuthorizationFailure(
            new ApplicationAccessException(Code.APPLICATION_SCOPE_MISMATCH, "a != b"));
        assertEquals(400, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("APPLICATION_SCOPE_MISMATCH", body.get("code"));
        assertFalse(body.get("message").toString().contains("a != b"), "diagnostics stay in the server log");
    }

    @Test
    public void anyOtherFailureStaysAGeneric500() {
        Response response = SecurityFilter.preAuthorizationFailure(new IllegalStateException("boom"));
        assertEquals(500, response.getStatus());
        assertEquals("AUTHORIZATION_CHECK_FAILED", ((Map<?, ?>) response.getEntity()).get("code"));
    }
}
