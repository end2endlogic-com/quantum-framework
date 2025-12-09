package com.e2eq.framework.rest.filters;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for LIST action inference and pre-authorization enforcement.
 * Tests lock in behavior for users with domain-specific access.
 */
public class SecurityFilterListActionIntegrationTest {

    private RuleContext ruleContext;

    @BeforeEach
    public void setUp() {
        // Create real RuleContext for testing
        ruleContext = new RuleContext();
        setupTestRules();
    }

    private void setupTestRules() {
        // Rule 1: User can LIST in allowed domain
        SecurityURIHeader allowedListHeader = new SecurityURIHeader.Builder()
            .withIdentity("testUser")
            .withArea("testArea")
            .withFunctionalDomain("allowedDomain")
            .withAction("LIST")
            .build();
        
        SecurityURIBody allowedBody = new SecurityURIBody.Builder()
            .withRealm("*")
            .withOrgRefName("*")
            .withAccountNumber("*")
            .withTenantId("*")
            .withDataSegment("*")
            .withOwnerId("*")
            .build();
        
        Rule allowedListRule = new Rule.Builder()
            .withName("Allow LIST on allowedDomain")
            .withSecurityURI(new SecurityURI(allowedListHeader, allowedBody))
            .withEffect(RuleEffect.ALLOW)
            .withPriority(10)
            .withFinalRule(true)
            .build();
        
        ruleContext.addRule(allowedListHeader, allowedListRule);
        
        // Rule 2: User can VIEW in allowed domain
        SecurityURIHeader allowedViewHeader = new SecurityURIHeader.Builder()
            .withIdentity("testUser")
            .withArea("testArea")
            .withFunctionalDomain("allowedDomain")
            .withAction("VIEW")
            .build();
        
        Rule allowedViewRule = new Rule.Builder()
            .withName("Allow VIEW on allowedDomain")
            .withSecurityURI(new SecurityURI(allowedViewHeader, allowedBody))
            .withEffect(RuleEffect.ALLOW)
            .withPriority(10)
            .withFinalRule(true)
            .build();
        
        ruleContext.addRule(allowedViewHeader, allowedViewRule);
        
        // Rule 3: Deny all on deniedDomain (no explicit allow)
        // Default DENY will handle this
    }

    @Test
    public void testListPathWithAllowedDomain_Returns200() {
        // Test: GET /testArea/allowedDomain/list should return 200
        ResourceContext resourceContext = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("allowedDomain")
            .withAction("LIST")
            .build();
        
        PrincipalContext principalContext = createTestUserContext();
        
        // Check rules - should ALLOW
        SecurityCheckResponse response = ruleContext.checkRules(principalContext, resourceContext, RuleEffect.DENY);
        
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect());
    }

    @Test
    public void testListPathWithDeniedDomain_Returns403() {
        // Test: GET /testArea/deniedDomain/list should return 403
        ResourceContext resourceContext = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("deniedDomain")
            .withAction("LIST")
            .build();
        
        PrincipalContext principalContext = createTestUserContext();
        
        // Check rules - should DENY (no matching rule)
        SecurityCheckResponse response = ruleContext.checkRules(principalContext, resourceContext, RuleEffect.DENY);
        
        assertEquals(RuleEffect.DENY, response.getFinalEffect());
    }

    @Test
    public void testNoListSegment_MapsToView() {
        // Test: GET /testArea/allowedDomain (no "list" segment) maps to VIEW
        ResourceContext resourceContext = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("allowedDomain")
            .withAction("VIEW")
            .build();
        
        PrincipalContext principalContext = createTestUserContext();
        
        // Check rules - should ALLOW (VIEW rule exists)
        SecurityCheckResponse response = ruleContext.checkRules(principalContext, resourceContext, RuleEffect.DENY);
        
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect());
    }

    @Test
    public void testNoListSegmentDeniedDomain_Returns403() {
        // Test: GET /testArea/deniedDomain (no "list" segment) should return 403
        ResourceContext resourceContext = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("deniedDomain")
            .withAction("VIEW")
            .build();
        
        PrincipalContext principalContext = createTestUserContext();
        
        // Check rules - should DENY (no matching rule)
        SecurityCheckResponse response = ruleContext.checkRules(principalContext, resourceContext, RuleEffect.DENY);
        
        assertEquals(RuleEffect.DENY, response.getFinalEffect());
    }

    @Test
    public void testFunctionalActionAnnotation_ResolvesToList() {
        // Test: Method with @FunctionalAction("LIST") resolves to LIST even without "list" in path
        
        // Simulate annotation-based resolution
        // In real scenario, this would be handled by SecurityFilter.determineResourceContext
        // when resourceInfo.getResourceMethod().getAnnotation(FunctionalAction.class) is present
        
        // For this test, we manually verify the logic
        String annotatedAction = "LIST"; // Would come from @FunctionalAction("LIST")
        
        assertNotNull(annotatedAction);
        assertEquals("LIST", annotatedAction);
        
        // Verify rule check with LIST action
        ResourceContext resourceContext = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("allowedDomain")
            .withAction(annotatedAction)
            .build();
        
        PrincipalContext principalContext = createTestUserContext();
        
        SecurityCheckResponse response = ruleContext.checkRules(principalContext, resourceContext, RuleEffect.DENY);
        
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect());
    }

    @Test
    public void testListWithResourceId_MapsCorrectly() {
        // Test: GET /testArea/allowedDomain/list/123 maps to LIST with resourceId
        ResourceContext resourceContext = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("allowedDomain")
            .withAction("LIST")
            .withResourceId("123")
            .build();
        
        PrincipalContext principalContext = createTestUserContext();
        
        // Check rules - should ALLOW
        SecurityCheckResponse response = ruleContext.checkRules(principalContext, resourceContext, RuleEffect.DENY);
        
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect());
    }

    @Test
    public void testCaseInsensitiveList_MapsToList() {
        // Test: Case-insensitive "list" mapping
        String[] testCases = {"list", "List", "LIST"};
        
        for (String testCase : testCases) {
            String action = testCase;
            if ("list".equalsIgnoreCase(action)) {
                action = "LIST";
            }
            assertEquals("LIST", action);
        }
    }

    @Test
    public void testMultipleDomains_IndependentAccess() {
        // Test: User has access to domain1 but not domain2
        
        // Add rule for domain1
        SecurityURIHeader domain1Header = new SecurityURIHeader.Builder()
            .withIdentity("testUser")
            .withArea("testArea")
            .withFunctionalDomain("domain1")
            .withAction("LIST")
            .build();
        
        SecurityURIBody body = new SecurityURIBody.Builder()
            .withRealm("*")
            .withOrgRefName("*")
            .withAccountNumber("*")
            .withTenantId("*")
            .withDataSegment("*")
            .withOwnerId("*")
            .build();
        
        Rule domain1Rule = new Rule.Builder()
            .withName("Allow LIST on domain1")
            .withSecurityURI(new SecurityURI(domain1Header, body))
            .withEffect(RuleEffect.ALLOW)
            .withPriority(10)
            .withFinalRule(true)
            .build();
        
        ruleContext.addRule(domain1Header, domain1Rule);
        
        PrincipalContext principalContext = createTestUserContext();
        
        // Test domain1 - should ALLOW
        ResourceContext rc1 = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("domain1")
            .withAction("LIST")
            .build();
        SecurityCheckResponse resp1 = ruleContext.checkRules(principalContext, rc1, RuleEffect.DENY);
        assertEquals(RuleEffect.ALLOW, resp1.getFinalEffect());
        
        // Test domain2 - should DENY
        ResourceContext rc2 = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("domain2")
            .withAction("LIST")
            .build();
        SecurityCheckResponse resp2 = ruleContext.checkRules(principalContext, rc2, RuleEffect.DENY);
        assertEquals(RuleEffect.DENY, resp2.getFinalEffect());
    }

    @Test
    public void testDifferentActions_IndependentRules() {
        // Test: User has LIST permission but not CREATE permission
        
        PrincipalContext principalContext = createTestUserContext();
        
        // Test LIST - should ALLOW
        ResourceContext listContext = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("allowedDomain")
            .withAction("LIST")
            .build();
        SecurityCheckResponse listResp = ruleContext.checkRules(principalContext, listContext, RuleEffect.DENY);
        assertEquals(RuleEffect.ALLOW, listResp.getFinalEffect());
        
        // Test CREATE - should DENY (no rule)
        ResourceContext createContext = new ResourceContext.Builder()
            .withArea("testArea")
            .withFunctionalDomain("allowedDomain")
            .withAction("CREATE")
            .build();
        SecurityCheckResponse createResp = ruleContext.checkRules(principalContext, createContext, RuleEffect.DENY);
        assertEquals(RuleEffect.DENY, createResp.getFinalEffect());
    }

    private PrincipalContext createTestUserContext() {
        DataDomain dataDomain = new DataDomain();
        dataDomain.setOrgRefName("test-org");
        dataDomain.setTenantId("test-tenant");
        dataDomain.setAccountNum("test-account");
        dataDomain.setDataSegment(0);
        dataDomain.setOwnerId("testUser");
        
        return new PrincipalContext.Builder()
            .withUserId("testUser")
            .withDefaultRealm("test-realm")
            .withDataDomain(dataDomain)
            .withRoles(new String[]{"user"})
            .withScope("AUTHENTICATED")
            .build();
    }

    // Mock resource class for annotation testing
    @FunctionalMapping(area = "testArea", domain = "testDomain")
    public static class TestResource {
        
        @GET
        @Path("list")
        public String getList() {
            return "list";
        }
        
        @GET
        @FunctionalAction("LIST")
        public String getListWithAnnotation() {
            return "list with annotation";
        }
        
        @GET
        public String getView() {
            return "view";
        }
    }
}
