package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for evalMode and SCOPED/EXACT/DEFAULT semantics when a rule with filters is present.
 * These tests avoid DB-side getFilters; they only verify check response scoping fields.
 */
@QuarkusTest
public class CheckEvalModeTest extends BaseRepoTest {

    @Inject
    RuleContext ruleContext;

    @Inject
    PolicyRepo policyRepo;

    @Inject
    SecurityUtils securityUtils;

    @AfterEach
    void clearContexts() {
        SecurityContext.clear();
    }

    private void installSimpleFilterAllowPolicy(String realm) {
        Policy p = new Policy();
        p.setRefName("scoped-policy");
        p.setDisplayName("Scoped Policy");
        p.setPrincipalId("user");
        p.setDataDomain(securityUtils.getSystemDataDomain());

        SecurityURIHeader hdr = new SecurityURIHeader.Builder()
                .withIdentity("user").withArea("sales").withFunctionalDomain("order").withAction("view").build();
        SecurityURIBody bdy = new SecurityURIBody.Builder()
                .withRealm("*").withOrgRefName("*").withAccountNumber("*")
                .withTenantId("*").withOwnerId("*").withDataSegment("*")
                .withResourceId("*").build();

        Rule r = new Rule.Builder()
                .withName("user-sales-order-view-filter")
                .withSecurityURI(new SecurityURI(hdr, bdy))
                .withAndFilterString("customerId:^[${accessibleCustomerIds}]")
                .withEffect(RuleEffect.ALLOW)
                .withPriority(100)
                .withFinalRule(false)
                .build();
        p.getRules().add(r);
        policyRepo.save(realm, p);
        ruleContext.reloadFromRepo(realm);
    }

    private PrincipalContext pc(String realm) {
        return new PrincipalContext.Builder()
                .withDefaultRealm(realm)
                .withUserId("alice@end2endlogic.com")
                .withRoles(new String[]{"user"})
                .withDataDomain(new DataDomain("end2endlogic", "0000000001", "tenant-1", 0, "alice"))
                .withScope("AUTHENTICATED")
                .build();
    }

    private ResourceContext rc() {
        return new ResourceContext.Builder()
                .withArea("sales").withFunctionalDomain("order").withAction("view")
                .withOwnerId("alice").withResourceId("ORD-1").build();
    }

    @Test
    @ActivateRequestContext
    @TestSecurity(user = "alice@end2endlogic.com", roles = {"user"})
    public void legacy_no_resource_returns_scoped_decision() {
        String realm = testUtils.getTestRealm();
        installSimpleFilterAllowPolicy(realm);

        SecurityCheckResponse resp = ruleContext.checkRules(
                pc(realm), rc(), null, null, RuleEffect.DENY,
                EvalMode.LEGACY
        );

        assertNotNull(resp);
        assertEquals("ALLOW", resp.getDecision(), "Effect should reflect matching rule's effect");
        assertEquals("SCOPED", resp.getDecisionScope(), "Without resource, decision should be SCOPED when rule has filters");
        assertTrue(resp.isScopedConstraintsPresent(), "SCOPED constraints should be present");
        assertFalse(resp.getScopedConstraints().isEmpty(), "SCOPED constraints should enumerate filters/scripts");
        assertNotNull(resp.getWinningRuleName(), "Should expose winning rule name for SCOPED decision");
        assertFalse(resp.getWinningRuleName().isBlank(), "Winning rule name should be non-empty for SCOPED");
    }

    @Test
    @ActivateRequestContext
    @TestSecurity(user = "alice@end2endlogic.com", roles = {"user"})
    public void strict_no_resource_returns_scoped_decision() {
        String realm = testUtils.getTestRealm();
        installSimpleFilterAllowPolicy(realm);

        SecurityCheckResponse resp = ruleContext.checkRules(
                pc(realm), rc(), null, null, RuleEffect.DENY,
                EvalMode.STRICT
        );

        assertNotNull(resp);
        assertEquals("ALLOW", resp.getDecision(), "Effect should reflect matching rule's effect");
        assertEquals("SCOPED", resp.getDecisionScope(), "STRICT without resource should surface SCOPED");
        assertTrue(resp.isScopedConstraintsPresent(), "SCOPED constraints should be present");
        assertFalse(resp.getScopedConstraints().isEmpty(), "SCOPED constraints should enumerate filters/scripts");
        assertEquals(EvalMode.STRICT.name(), resp.getEvalModeUsed());
        assertNotNull(resp.getWinningRuleName(), "Should expose winning rule name for SCOPED decision");
        assertFalse(resp.getWinningRuleName().isBlank(), "Winning rule name should be non-empty for SCOPED");
    }
}
