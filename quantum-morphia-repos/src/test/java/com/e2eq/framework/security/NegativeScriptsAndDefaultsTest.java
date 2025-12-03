package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.securityrules.RuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Negative and edge cases for RuleContext: malformed scripts and empty rules set.
 */
public class NegativeScriptsAndDefaultsTest {

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
    void malformed_precondition_script_causes_rule_to_be_ignored_safely() {
        // Broad DENY for orders/*/*
        Rule broadDeny = new Rule.Builder()
                .withName("broad-deny")
                .withSecurityURI(new SecurityURI(
                        new SecurityURIHeader.Builder()
                                .withIdentity("user")
                                .withArea("orders")
                                .withFunctionalDomain("*")
                                .withAction("*")
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
                .withEffect(RuleEffect.DENY)
                .withPriority(5)
                .build();

        // Specific ALLOW with malformed script → should evaluate to false and not apply
        String badScript = "identityInfo != null && ("; // intentionally malformed JS
        Rule brokenAllow = new Rule.Builder()
                .withName("broken-allow")
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
                .withPreconditionScript(badScript)
                .withEffect(RuleEffect.ALLOW)
                .withPriority(10)
                .build();

        ruleContext.addRule(broadDeny.getSecurityURI().getHeader(), broadDeny);
        ruleContext.addRule(brokenAllow.getSecurityURI().getHeader(), brokenAllow);

        ResourceContext rc = new ResourceContext.Builder()
                .withArea("orders")
                .withFunctionalDomain("order")
                .withAction("view")
                .withResourceId("o-1")
                .withOwnerId(principal.getUserId())
                .build();

        SecurityCheckResponse resp = ruleContext.checkRules(principal, rc);
        // Expect DENY because the ALLOW rule's malformed script should make it not applicable
        assertEquals(RuleEffect.DENY, resp.getFinalEffect());
    }

    @Test
    void empty_ruleset_defaults_to_deny() {
        ResourceContext rc = new ResourceContext.Builder()
                .withArea("unknown")
                .withFunctionalDomain("none")
                .withAction("view")
                .withResourceId("x")
                .withOwnerId(principal.getUserId())
                .build();
        SecurityCheckResponse resp = ruleContext.checkRules(principal, rc);
        assertEquals(RuleEffect.DENY, resp.getFinalEffect());
    }
}
