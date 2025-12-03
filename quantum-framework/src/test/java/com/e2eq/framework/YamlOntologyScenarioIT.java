package com.e2eq.framework;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.io.YamlRuleLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3: YAML-authored scenario tests for simple ontology-like flows
 * order -> order_line -> customer -> shipment
 */
public class YamlOntologyScenarioIT {

    private final YamlRuleLoader loader = new YamlRuleLoader();
    private RuleContext ruleContext;
    private PrincipalContext principal;

    @BeforeEach
    void setup() {
        ruleContext = new RuleContext();
        ruleContext.clear();

        DataDomain dd = new DataDomain("end2endlogic.com", "0000000001", "end2endlogic.com", 0, "user@end2endlogic.com");
        principal = new PrincipalContext.Builder()
                .withDefaultRealm("b2bi")
                .withDataDomain(dd)
                .withUserId("user@end2endlogic.com")
                .withRoles(new String[]{"user"})
                .build();
    }

    @Test
    void yaml_rules_drive_expected_outcomes_across_ontology_domains() throws Exception {
        try (InputStream is = res("/rules_ontology_scenarios.yaml")) {
            List<Rule> rules = loader.load(is);
            assertTrue(rules.size() >= 3, "Expected at least 3 expanded rules");

            for (Rule r : rules) {
                ruleContext.addRule(r.getSecurityURI().getHeader(), r);
            }

            // orders area broad DENY, but order_line view/list explicitly ALLOW with higher specificity/priority
            assertAllowed("orders", "order_line", "view");
            assertAllowed("orders", "order_line", "list");
            assertDenied("orders", "order", "delete");
            assertDenied("orders", "shipment", "view");

            // scripted ALLOW for order view
            assertAllowed("orders", "order", "view");

            // logistics: customer view allowed; shipment view denied
            assertAllowed("logistics", "customer", "view");
            assertDenied("logistics", "shipment", "view");
        }
    }

    private void assertAllowed(String area, String domain, String action) {
        SecurityCheckResponse resp = check(area, domain, action);
        assertEquals(RuleEffect.ALLOW, resp.getFinalEffect(), () -> "Expected ALLOW for " + area + "/" + domain + "/" + action);
    }

    private void assertDenied(String area, String domain, String action) {
        SecurityCheckResponse resp = check(area, domain, action);
        assertEquals(RuleEffect.DENY, resp.getFinalEffect(), () -> "Expected DENY for " + area + "/" + domain + "/" + action);
    }

    private SecurityCheckResponse check(String area, String domain, String action) {
        ResourceContext rc = new ResourceContext.Builder()
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .withResourceId("res-42")
                .withOwnerId(principal.getUserId())
                .build();
        return ruleContext.checkRules(principal, rc);
    }

    private static InputStream res(String path) {
        InputStream is = YamlOntologyScenarioIT.class.getResourceAsStream(path);
        assertNotNull(is, "Missing resource: " + path);
        return is;
    }
}
