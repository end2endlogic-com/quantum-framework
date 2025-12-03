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
 * YAML scenario: role-based rules and explicit no-match behavior (default DENY).
 */
public class YamlRolesAndNoMatchIT {

    private final YamlRuleLoader loader = new YamlRuleLoader();
    private RuleContext ruleContext;
    private PrincipalContext admin;
    private PrincipalContext user;

    @BeforeEach
    void setup() {
        ruleContext = new RuleContext();
        ruleContext.clear();

        DataDomain dd = new DataDomain("end2endlogic.com", "0000000001", "end2endlogic.com", 0, "principal@end2endlogic.com");
        admin = new PrincipalContext.Builder()
                .withDefaultRealm("b2bi")
                .withDataDomain(dd)
                .withUserId("admin@end2endlogic.com")
                .withRoles(new String[]{"admin"})
                .build();

        user = new PrincipalContext.Builder()
                .withDefaultRealm("b2bi")
                .withDataDomain(dd)
                .withUserId("user@end2endlogic.com")
                .withRoles(new String[]{"user"})
                .build();
    }

    @Test
    void role_based_rules_apply_and_no_match_defaults_to_deny() throws Exception {
        try (InputStream is = res("/rules_roles_and_no_match.yaml")) {
            List<Rule> rules = loader.load(is);
            assertTrue(rules.size() >= 2, "Expected at least 2 expanded rules");

            for (Rule r : rules) {
                ruleContext.addRule(r.getSecurityURI().getHeader(), r);
            }

            // admin role: finance/ledger view allowed per YAML
            assertAllowed(admin, "finance", "ledger", "view");
            // admin role: security/userProfile list allowed per YAML
            assertAllowed(admin, "security", "userProfile", "list");

            // user role has no matching rules in this YAML -> DENY
            assertDenied(user, "finance", "ledger", "view");
            assertDenied(user, "security", "userProfile", "list");
            // and any other unmatched area/domain/action is DENY
            assertDenied(user, "orders", "order", "delete");
        }
    }

    private void assertAllowed(PrincipalContext pctx, String area, String domain, String action) {
        SecurityCheckResponse resp = check(pctx, area, domain, action);
        assertEquals(RuleEffect.ALLOW, resp.getFinalEffect(), () -> pctx.getUserId() + " -> ALLOW expected for " + area + "/" + domain + "/" + action);
    }

    private void assertDenied(PrincipalContext pctx, String area, String domain, String action) {
        SecurityCheckResponse resp = check(pctx, area, domain, action);
        assertEquals(RuleEffect.DENY, resp.getFinalEffect(), () -> pctx.getUserId() + " -> DENY expected for " + area + "/" + domain + "/" + action);
    }

    private SecurityCheckResponse check(PrincipalContext pctx, String area, String domain, String action) {
        ResourceContext rc = new ResourceContext.Builder()
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .withResourceId("res-x")
                .withOwnerId(pctx.getUserId())
                .build();
        return ruleContext.checkRules(pctx, rc);
    }

    private static InputStream res(String path) {
        InputStream is = YamlRolesAndNoMatchIT.class.getResourceAsStream(path);
        assertNotNull(is, "Missing resource: " + path);
        return is;
    }
}
