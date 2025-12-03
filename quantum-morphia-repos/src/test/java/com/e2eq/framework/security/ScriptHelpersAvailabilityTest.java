package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.securityrules.RuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that RuleContext.runScript installs baseline helper bindings and that
 * simple scripts can evaluate using exposed variables (without relying on optional ontology helpers).
 */
public class ScriptHelpersAvailabilityTest {

    RuleContext ruleContext;
    PrincipalContext principal;

    @BeforeEach
    void init() {
        ruleContext = new RuleContext();
        ruleContext.clear();

        DataDomain dd = new DataDomain("end2endlogic.com", "0000000001", "end2endlogic.com", 0, "u1@end2endlogic.com");
        principal = new PrincipalContext.Builder()
                .withDefaultRealm("b2bi")
                .withDataDomain(dd)
                .withUserId("u1@end2endlogic.com")
                .withRoles(new String[]{"user"})
                .build();
    }

    @Test
    void precondition_script_can_access_identityInfo_and_context_objects() {
        // Create a rule that only applies if identityInfo.userId is non-null and resource context has action "view"
        String script = "identityInfo != null && identityInfo.userId != null && rcontext != null && rcontext != undefined;";
        Rule scriptedAllow = new Rule.Builder()
                .withName("scripted-allow")
                .withSecurityURI(new SecurityURI(
                        new SecurityURIHeader.Builder()
                                .withIdentity("user")
                                .withArea("orders")
                                .withFunctionalDomain("order")
                                .withAction("view")
                                .build(),
                        new SecurityURIBody.Builder()
                                .withOrgRefName("*")
                                .withAccountNumber("*")
                                .withRealm("*")
                                .withTenantId("*")
                                .withOwnerId("*")
                                .withDataSegment("*")
                                .withResourceId("*")
                                .build()))
                .withPreconditionScript(script)
                .withEffect(RuleEffect.ALLOW)
                .withPriority(10)
                .build();

        ruleContext.addRule(scriptedAllow.getSecurityURI().getHeader(), scriptedAllow);

        ResourceContext rc = new ResourceContext.Builder()
                .withArea("orders")
                .withFunctionalDomain("order")
                .withAction("view")
                .withResourceId("o-1")
                .withOwnerId(principal.getUserId())
                .build();

        SecurityCheckResponse resp = ruleContext.checkRules(principal, rc);
        assertEquals(RuleEffect.ALLOW, resp.getFinalEffect());
    }
}
