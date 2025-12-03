package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.securityrules.RuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1: Unit tests for RuleContext prioritization, matching, and defaults (no YAML/DB I/O).
 */
public class RuleContextPrioritizationAndMatchingTest {

    RuleContext ruleContext;
    PrincipalContext principal;

    @BeforeEach
    void setUp() {
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
    void specificityOrdering_exact_beats_wildcard_and_higher_priority_beats_lower_when_tied() {
        // Wildcard ALLOW at area=orders, domain=*, action=*
        Rule broadAllow = rule("broad-allow", header("user", "orders", "*", "*"), RuleEffect.ALLOW, 5, false);
        ruleContext.addRule(broadAllow.getSecurityURI().getHeader(), broadAllow);

        // Exact DENY for orders/order/delete with equal priority -> exact match should take precedence
        Rule exactDeny = rule("exact-deny", header("user", "orders", "order", "delete"), RuleEffect.DENY, 5, false);
        ruleContext.addRule(exactDeny.getSecurityURI().getHeader(), exactDeny);

        // When tied on specificity, higher priority number wins
        Rule exactAllowLowerPriority = rule("exact-allow-low", header("user", "orders", "order", "update"), RuleEffect.ALLOW, 5, false);
        Rule exactDenyHigherPriority = rule("exact-deny-high", header("user", "orders", "order", "update"), RuleEffect.DENY, 10, false);
        ruleContext.addRule(exactAllowLowerPriority.getSecurityURI().getHeader(), exactAllowLowerPriority);
        ruleContext.addRule(exactDenyHigherPriority.getSecurityURI().getHeader(), exactDenyHigherPriority);

        // Check: delete -> DENY (exact beats wildcard)
        assertFinal("orders", "order", "delete", RuleEffect.DENY);
        // Check: update -> DENY (priority 10 beats 5 for same scope)
        assertFinal("orders", "order", "update", RuleEffect.DENY);
        // Check: view (no exact), wildcard ALLOW applies
        assertFinal("orders", "shipment", "view", RuleEffect.ALLOW);
    }

    @Test
    void fallback_matching_applies_and_no_match_uses_default_effect() {
        // Only define domain-specific rule for security/userProfile view
        Rule viewAllow = rule("view-allow", header("user", "security", "userProfile", "view"), RuleEffect.ALLOW, 10, false);
        ruleContext.addRule(viewAllow.getSecurityURI().getHeader(), viewAllow);

        // Exact match -> ALLOW
        assertFinal("security", "userProfile", "view", RuleEffect.ALLOW);
        // Different action -> no exact rule, expect default DENY
        assertFinal("security", "userProfile", "delete", RuleEffect.DENY);
        // Different domain -> no rule matched, default DENY
        assertFinal("security", "group", "view", RuleEffect.DENY);
    }

    private void assertFinal(String area, String domain, String action, RuleEffect expected) {
        ResourceContext rc = new ResourceContext.Builder()
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .withResourceId("res-1")
                .withOwnerId(principal.getUserId())
                .build();
        SecurityCheckResponse resp = ruleContext.checkRules(principal, rc); // default DENY inside
        assertEquals(expected, resp.getFinalEffect(), () -> area + "/" + domain + "/" + action);
    }

    private static Rule rule(String name, SecurityURIHeader header, RuleEffect effect, int priority, boolean fin) {
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withOrgRefName("*")
                .withAccountNumber("*")
                .withRealm("*")
                .withTenantId("*")
                .withOwnerId("*")
                .withDataSegment("*")
                .withResourceId("*")
                .build();
        SecurityURI uri = new SecurityURI(header, body);
        return new Rule.Builder()
                .withName(name)
                .withSecurityURI(uri)
                .withEffect(effect)
                .withPriority(priority)
                .withFinalRule(fin)
                .build();
    }

    private static SecurityURIHeader header(String identity, String area, String domain, String action) {
        return new SecurityURIHeader.Builder()
                .withIdentity(identity)
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .build();
    }
}
