package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.util.SecurityUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Remote-mode tier-2 services write the control-plane realm catalog and memberships
 * with the system service token (role "system"), for the data domain of the tenant
 * being provisioned. The default system rules must admit exactly that, and nothing
 * outside the control-plane directory.
 */
class RuleContextSystemRulesTest {

    private static RuleContext withSystemRules() {
        SecurityUtils securityUtils = mock(SecurityUtils.class);
        when(securityUtils.getSystemSecurityHeader()).thenReturn(new SecurityURIHeader.Builder()
            .withIdentity("system@system.com").withArea("security").withFunctionalDomain("*").withAction("*").build());
        when(securityUtils.getSystemSecurityBody()).thenReturn(new SecurityURIBody.Builder()
            .withRealm("quantum-auth").withOrgRefName("system.com").withAccountNumber("0000000000")
            .withTenantId("system.com").withOwnerId("system@system.com").withDataSegment("*").build());
        RuleContext context = new RuleContext(securityUtils, null);
        context.addSystemRules();
        return context;
    }

    private static PrincipalContext systemServiceToken() {
        return new PrincipalContext.Builder()
            .withDefaultRealm("quantum-system")
            .withDataDomain(new DataDomain("system.com", "0000000000", "system.com", 0, "system@system.com"))
            .withUserId("2d34c41a-0d77-43ba-8472-b1cba46c66b2")
            .withRoles(new String[]{"system"})
            .withScope("AUTHENTICATED")
            .build();
    }

    private static ResourceContext directoryWrite(String area, String domain) {
        return new ResourceContext.Builder()
            .withRealm("quantum-system")
            .withArea(area)
            .withFunctionalDomain(domain)
            .withAction("APPLY")
            .withDataDomain(new DataDomain("personal-abc", "personal-abc", "personal-abc-invalid", 0, "person@example.com"))
            .build();
    }

    @Test
    void declaresTheControlPlaneDirectoryRuleForTheSystemRole() {
        List<Rule> rules = withSystemRules().rulesForIdentity("system").orElseThrow();
        Rule rule = rules.stream().filter(r -> "SysRoleControlPlaneDirectory".equals(r.getName())).findFirst().orElseThrow();
        assertEquals("system", rule.getSecurityURI().getHeader().getArea());
        assertEquals(RuleContext.CONTROL_PLANE_DIRECTORY_DOMAIN, rule.getSecurityURI().getHeader().getFunctionalDomain());
        assertEquals("*", rule.getSecurityURI().getHeader().getAction());
        assertEquals(RuleEffect.ALLOW, rule.getEffect());
        assertEquals(-100, rule.getPriority());
        assertTrue(rule.isFinalRule());
    }

    @Test
    void systemRoleMayWriteAnotherTenantsMembershipInTheDirectory() {
        var response = withSystemRules().checkRules(systemServiceToken(),
            directoryWrite("system", RuleContext.CONTROL_PLANE_DIRECTORY_DOMAIN));
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect());
    }

    @Test
    void theRuleDoesNotReachOutsideTheDirectory() {
        RuleContext context = withSystemRules();
        assertNotEquals(RuleEffect.ALLOW,
            context.checkRules(systemServiceToken(), directoryWrite("system", "install")).getFinalEffect());
        assertNotEquals(RuleEffect.ALLOW,
            context.checkRules(systemServiceToken(), directoryWrite("billing", RuleContext.CONTROL_PLANE_DIRECTORY_DOMAIN)).getFinalEffect());
    }
}
