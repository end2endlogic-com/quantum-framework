package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleDeterminedEffect;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.RuleResult;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.e2eq.framework.security.runtime.RuleContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleContextFieldPolicyTest {

    @Test
    void exclusions_are_collected_only_from_applicable_allow_rules() {
        PrincipalContext principal = new PrincipalContext.Builder().withUserId("field-policy-user").build();
        ResourceContext resource = new ResourceContext.Builder()
                .withArea("test")
                .withFunctionalDomain("record")
                .withAction("view")
                .build();

        Rule allow = new Rule.Builder()
                .withName("allow-with-exclusion")
                .withEffect(RuleEffect.ALLOW)
                .withExcludedFields(List.of(" description ", "nested.secret"))
                .build();
        Rule deny = new Rule.Builder()
                .withName("losing-deny-with-exclusion")
                .withEffect(RuleEffect.DENY)
                .withExcludedFields(List.of("must.not.apply"))
                .build();

        RuleResult allowResult = new RuleResult(allow);
        allowResult.setDeterminedEffect(RuleDeterminedEffect.ALLOW);
        RuleResult denyResult = new RuleResult(deny);
        denyResult.setDeterminedEffect(RuleDeterminedEffect.DENY);

        SecurityCheckResponse response = new SecurityCheckResponse(principal, resource);
        response.setFinalEffect(RuleEffect.ALLOW);
        response.setMatchedRuleResults(List.of(allowResult, denyResult));

        RuleContext ruleContext = new RuleContext() {
            @Override
            public SecurityCheckResponse checkRules(PrincipalContext ignoredPrincipal, ResourceContext ignoredResource) {
                return response;
            }
        };

        assertEquals(Set.of("description", "nested.secret"),
                ruleContext.getExcludedFieldPaths(principal, resource));
    }
}
