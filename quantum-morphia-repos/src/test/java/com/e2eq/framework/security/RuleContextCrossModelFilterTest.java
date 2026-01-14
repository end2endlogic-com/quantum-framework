package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.securityrules.RuleContext;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RuleContext.getFilters() behavior when rules contain model-specific
 * resolver variables that may not be resolvable for all model classes.
 *
 * This addresses the issue where rules matched by area/domain/action are incorrectly
 * applied to queries for model classes that don't support the rule's resolver variables.
 */
public class RuleContextCrossModelFilterTest {

    RuleContext ruleContext;
    PrincipalContext principal;

    // Mock model classes for testing
    static class LocationModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalDomain() { return "locations"; }
        @Override
        public String bmFunctionalArea() { return "location_hub"; }
    }

    static class UserProfileModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalDomain() { return "userProfiles"; }
        @Override
        public String bmFunctionalArea() { return "security"; }
    }

    static class JobModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalDomain() { return "jobs"; }
        @Override
        public String bmFunctionalArea() { return "job_hub"; }
    }

    @BeforeEach
    void setUp() {
        ruleContext = new RuleContext();
        ruleContext.clear();

        DataDomain dd = new DataDomain("test.com", "0000000001", "test.com", 0, "user@test.com");
        principal = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(dd)
                .withUserId("user@test.com")
                .withRoles(new String[]{"user"})
                .build();
    }

    @Test
    void getFilters_skipsRuleWithUnresolvableVariable_forNonSupportedModelClass() {
        // Rule with a resolver variable - since no resolvers are registered,
        // this variable will be unresolved for all model classes
        Rule ruleWithVariable = createRule(
            "rule-with-variable",
            "location_hub", "locations", "list",
            "id:^[${unknownVariable}]",  // Uses unresolvable variable
            null,
            RuleEffect.ALLOW,
            10
        );
        ruleContext.addRule(ruleWithVariable.getSecurityURI().getHeader(), ruleWithVariable);

        ResourceContext rc = new ResourceContext.Builder()
                .withArea("location_hub")
                .withFunctionalDomain("locations")
                .withAction("list")
                .withResourceId("res-1")
                .withOwnerId(principal.getUserId())
                .build();

        // Test: Since the variable can't be resolved, the rule should be skipped gracefully
        // Before the fix, this would throw IllegalStateException
        List<Filter> filters = ruleContext.getFilters(
            new ArrayList<>(),
            principal,
            rc,
            LocationModel.class
        );

        // Rule with unresolvable variable should be skipped
        assertTrue(filters.isEmpty(),
            "Rule with unresolvable variable should be skipped without error");
    }

    @Test
    void getFilters_appliesMultipleRules_skippingOnlyUnresolvableOnes() {
        // Rule 1: Generic rule with no variables (applies to all models)
        Rule genericRule = createRule(
            "generic-rule",
            "location_hub", "locations", "list",
            "status:active",  // No variables
            null,
            RuleEffect.ALLOW,
            10
        );
        ruleContext.addRule(genericRule.getSecurityURI().getHeader(), genericRule);

        // Rule 2: Rule with unresolvable variable
        Rule ruleWithVariable = createRule(
            "rule-with-variable",
            "location_hub", "locations", "list",
            "id:^[${unknownVariable}]",  // Uses unresolvable variable
            null,
            RuleEffect.ALLOW,
            9
        );
        ruleContext.addRule(ruleWithVariable.getSecurityURI().getHeader(), ruleWithVariable);

        ResourceContext rc = new ResourceContext.Builder()
                .withArea("location_hub")
                .withFunctionalDomain("locations")
                .withAction("list")
                .withResourceId("res-1")
                .withOwnerId(principal.getUserId())
                .build();

        // Only generic rule should apply (rule with variable skipped)
        List<Filter> filters = ruleContext.getFilters(
            new ArrayList<>(),
            principal,
            rc,
            LocationModel.class
        );
        assertEquals(1, filters.size(),
            "Should only have generic filter (rule with variable skipped)");
    }

    @Test
    void getFilters_handlesRuleWithBothAndOrFilters_oneUnresolvable() {
        // Rule with both AND and OR filter strings, where OR has unresolvable variable
        Rule mixedRule = createRule(
            "mixed-rule",
            "location_hub", "locations", "list",
            "status:active",  // AND filter - no variables
            "id:^[${unknownVariable}]",  // OR filter - unresolvable variable
            RuleEffect.ALLOW,
            10
        );
        ruleContext.addRule(mixedRule.getSecurityURI().getHeader(), mixedRule);

        ResourceContext rc = new ResourceContext.Builder()
                .withArea("location_hub")
                .withFunctionalDomain("locations")
                .withAction("list")
                .withResourceId("res-1")
                .withOwnerId(principal.getUserId())
                .build();

        // Entire rule should be skipped (partial failure - one filter unresolvable)
        List<Filter> filters = ruleContext.getFilters(
            new ArrayList<>(),
            principal,
            rc,
            LocationModel.class
        );
        assertTrue(filters.isEmpty(),
            "Rule should be skipped when one filter is unresolvable");
    }

    @Test
    void getFilters_doesNotLoseFiltersFromPreviousRules_whenLaterRuleFails() {
        // Rule 1: No variables, processes successfully
        Rule rule1 = createRule(
            "rule-1",
            "location_hub", "locations", "list",
            "status:active",
            null,
            RuleEffect.ALLOW,
            10
        );
        ruleContext.addRule(rule1.getSecurityURI().getHeader(), rule1);

        // Rule 2: Has unresolvable variable
        Rule rule2 = createRule(
            "rule-2",
            "location_hub", "locations", "list",
            "id:^[${unknownVariable}]",
            null,
            RuleEffect.ALLOW,
            9
        );
        ruleContext.addRule(rule2.getSecurityURI().getHeader(), rule2);

        // Rule 3: No variables, processes successfully
        Rule rule3 = createRule(
            "rule-3",
            "location_hub", "locations", "list",
            "archived:false",
            null,
            RuleEffect.ALLOW,
            8
        );
        ruleContext.addRule(rule3.getSecurityURI().getHeader(), rule3);

        ResourceContext rc = new ResourceContext.Builder()
                .withArea("location_hub")
                .withFunctionalDomain("locations")
                .withAction("list")
                .withResourceId("res-1")
                .withOwnerId(principal.getUserId())
                .build();

        // Rule 2 should fail, but Rules 1 and 3 should still apply
        List<Filter> filters = ruleContext.getFilters(
            new ArrayList<>(),
            principal,
            rc,
            LocationModel.class
        );
        assertEquals(2, filters.size(),
            "Should have filters from rule 1 and rule 3, skipping rule 2");
    }

    @Test
    void getFilters_rethrowsNonVariableExceptions() {
        // Rule with invalid filter syntax (not a variable resolution issue)
        Rule invalidRule = createRule(
            "invalid-rule",
            "location_hub", "locations", "list",
            "invalid{{{syntax",  // Malformed filter string
            null,
            RuleEffect.ALLOW,
            10
        );
        ruleContext.addRule(invalidRule.getSecurityURI().getHeader(), invalidRule);

        ResourceContext rc = new ResourceContext.Builder()
                .withArea("location_hub")
                .withFunctionalDomain("locations")
                .withAction("list")
                .withResourceId("res-1")
                .withOwnerId(principal.getUserId())
                .build();

        // Should throw exception (not caught as unresolved variable)
        assertThrows(Exception.class, () -> {
            ruleContext.getFilters(
                new ArrayList<>(),
                principal,
                rc,
                LocationModel.class
            );
        }, "Should rethrow exceptions that aren't about unresolved variables");
    }

    // Helper methods

    private Rule createRule(String name, String area, String domain, String action,
                           String andFilterString, String orFilterString,
                           RuleEffect effect, int priority) {
        SecurityURIHeader header = new SecurityURIHeader("user", area, domain, action);
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
                .withAndFilterString(andFilterString)
                .withOrFilterString(orFilterString)
                .build();
    }

}
