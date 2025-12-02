package com.e2eq.framework.security;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
public class TestRuleContextReload {

    @Inject
    RuleContext ruleContext;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    SecurityUtils securityUtils;

    @Test
    public void testRepoReloadAndSystemAllow() {
        String realm = envConfigUtils.getTestRealm();

        // Force a reload from the repository (seed data) for the test realm
        RuleContextTestUtils.reload(realm);

        // Build a principal context for the system user in the test realm
        String[] roles = new String[]{"admin", "system"};
        PrincipalContext pc = new PrincipalContext.Builder()
                .withDefaultRealm(realm)
                .withDataDomain(securityUtils.getSystemDataDomain())
                .withUserId(envConfigUtils.getSystemUserId())
                .withRoles(roles)
                .withScope("TEST")
                .build();

        // Request an action under security/userProfile/update which should be allowed by seeds
        ResourceContext rc = new ResourceContext.Builder()
                .withArea("security")
                .withFunctionalDomain("userProfile")
                .withAction("update")
                .build();

        SecurityCheckResponse resp = ruleContext.checkRules(pc, rc);
        assertEquals(RuleEffect.ALLOW, resp.getFinalEffect(), "system@system.com should be allowed by seeded policies");
    }
}
