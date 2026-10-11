package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.security.runtime.RuleContext;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RuleContext.getFilters() behavior when rules contain resolver variables that may
 * not be resolvable for every model class.
 *
 * A matched ALLOW rule whose filter cannot be resolved FAILS CLOSED: it contributes a
 * match-nothing filter instead of being dropped (dropping it would run the read without that
 * rule's constraint). Rules that should not apply to a model class must be scoped by
 * area/domain/action, not by an unresolvable variable.
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
    void getFilters_failsClosedForRuleWithUnresolvableVariable() {
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

        List<Filter> filters = ruleContext.getFilters(
            new ArrayList<>(),
            principal,
            rc,
            LocationModel.class
        );

        assertEquals(1, filters.size(), "Rule with unresolvable variable must not be dropped");
        assertMatchNothing(filters.get(0));
    }

    @Test
    void getFilters_appliesMultipleRules_unresolvableOneMatchesNothing() {
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

        // Generic rule applies AND the unresolvable rule contributes match-nothing
        List<Filter> filters = ruleContext.getFilters(
            new ArrayList<>(),
            principal,
            rc,
            LocationModel.class
        );
        assertEquals(2, filters.size(),
            "Should have the generic filter plus a match-nothing filter for the unresolvable rule");
        assertEquals(1, filters.stream().filter(RuleContextCrossModelFilterTest::isMatchNothing).count());
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

        // Partial failure (one filter unresolvable): the whole rule matches nothing
        List<Filter> filters = ruleContext.getFilters(
            new ArrayList<>(),
            principal,
            rc,
            LocationModel.class
        );
        assertEquals(1, filters.size(), "Rule must fail closed when one of its filters is unresolvable");
        assertMatchNothing(filters.get(0));
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

        // Rules 1 and 3 still apply; rule 2 contributes match-nothing
        List<Filter> filters = ruleContext.getFilters(
            new ArrayList<>(),
            principal,
            rc,
            LocationModel.class
        );
        assertEquals(3, filters.size(),
            "Should have filters from rule 1 and rule 3 plus match-nothing for rule 2");
        assertEquals(1, filters.stream().filter(RuleContextCrossModelFilterTest::isMatchNothing).count());
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

    private static boolean isMatchNothing(Filter f) {
        return "$in".equals(f.getName()) && "_id".equals(f.getField())
                && f.getValue() instanceof Collection<?> c && c.isEmpty();
    }

    private static void assertMatchNothing(Filter f) {
        assertTrue(isMatchNothing(f), "expected match-nothing filter (_id $in []), got " + f);
    }

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
