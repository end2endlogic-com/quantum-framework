package com.e2eq.framework.securityrules.io;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.securityrules.RuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style test: load rules from YAML via YamlRuleLoader and execute
 * authorization checks through RuleContext starting from a default DENY.
 */
public class YamlRuleLoaderRuleContextIT {

    private final YamlRuleLoader loader = new YamlRuleLoader();

    private RuleContext ruleContext;
    private PrincipalContext userPrincipal;

    @BeforeEach
    void setup() {
        // Fresh RuleContext without system defaults
        ruleContext = new RuleContext();
        ruleContext.clear();

        // Minimal principal context representing a normal user with role "user"
        DataDomain dd = new DataDomain(
                "end2endlogic.com", // orgRefName
                "0000000001",       // accountNum
                "end2endlogic.com", // tenantId
                0,                   // dataSegment
                "testuser@end2endlogic.com" // ownerId
        );

        userPrincipal = new PrincipalContext.Builder()
                .withDefaultRealm("b2bi")
                .withDataDomain(dd)
                .withUserId("testuser@end2endlogic.com")
                .withRoles(new String[]{"user"})
                .build();
    }

    @Test
    void loads_yaml_rules_and_checks_allow_deny_across_areas_and_domains() throws Exception {
        try (InputStream is = resource("/rules_access_matrix.yaml")) {
            List<Rule> rules = loader.load(is);
            assertNotNull(rules, "rules should not be null");
            // Expected expansion:
            // - allow-areas: areas [sales, inventory] x domain * x action * -> 2 expanded rules
            // - allow-userprofile-view-list: 1 x 1 x 1 x 2 -> 2 rules
            // - allow-billing-invoice-view-list: 1 x 1 x 1 x 2 -> 2 rules
            // Total = 6 rules
            assertEquals(6, rules.size(), "Unexpected expanded rule count");

            // Seed RuleContext with rules (keyed by header identity)
            for (Rule r : rules) {
                SecurityURIHeader h = r.getSecurityURI().getHeader();
                assertNotNull(h.getIdentity());
                ruleContext.addRule(h, r);
            }

            // Start from default DENY and verify behavior

            // 1) Whole-area access: sales/*/* allowed
            assertAllowed("sales", "anyDomain", "anyAction");
            // 2) Whole-area access: inventory/*/* allowed
            assertAllowed("inventory", "sku", "update");
            // 3) Unspecified area: finance -> DENY
            assertDenied("finance", "ledger", "view");

            // 4) Specific domain/actions: security:userProfile view/list allowed
            assertAllowed("security", "userProfile", "view");
            assertAllowed("security", "userProfile", "list");
            // 5) Other action in same domain: delete denied
            assertDenied("security", "userProfile", "delete");

            // 6) billing:invoice view/list allowed; other action denied
            assertAllowed("billing", "invoice", "view");
            assertAllowed("billing", "invoice", "list");
            assertDenied("billing", "invoice", "write");

            // 7) Other domain in billing should be denied
            assertDenied("billing", "payment", "view");
        }
    }

    private void assertAllowed(String area, String domain, String action) {
        SecurityCheckResponse resp = check(area, domain, action);
        assertEquals(RuleEffect.ALLOW, resp.getFinalEffect(),
                () -> String.format("Expected ALLOW for %s/%s/%s but was %s", area, domain, action, resp.getFinalEffect()));
    }

    private void assertDenied(String area, String domain, String action) {
        SecurityCheckResponse resp = check(area, domain, action);
        assertEquals(RuleEffect.DENY, resp.getFinalEffect(),
                () -> String.format("Expected DENY for %s/%s/%s but was %s", area, domain, action, resp.getFinalEffect()));
    }

    private SecurityCheckResponse check(String area, String domain, String action) {
        ResourceContext rc = new ResourceContext.Builder()
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .withResourceId("res-1")
                .withOwnerId(userPrincipal.getUserId())
                .build();
        return ruleContext.checkRules(userPrincipal, rc); // default DENY
    }

    private static InputStream resource(String path) {
        InputStream is = YamlRuleLoaderRuleContextIT.class.getResourceAsStream(path);
        assertNotNull(is, "Resource not found: " + path);
        return is;
    }
}
