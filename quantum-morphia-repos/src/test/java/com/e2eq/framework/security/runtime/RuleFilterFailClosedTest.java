package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.MatchEvent;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import dev.morphia.query.filters.Filter;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rule filter variables fail CLOSED: a matched ALLOW rule whose filter references a variable that
 * is missing, null, or whose resolver threw contributes a match-nothing filter (reads), makes the
 * rule not apply in in-memory evaluation, and denies writes. It is never dropped.
 */
class RuleFilterFailClosedTest {

    static class Widget extends UnversionedBaseModel {
        @Override
        public String bmFunctionalDomain() { return "widgets"; }
        @Override
        public String bmFunctionalArea() { return "widget_hub"; }
    }

    static final class FixedResolver implements AccessListResolver {
        private final String key;
        private final Collection<?> values;
        private final boolean fail;

        FixedResolver(String key, Collection<?> values, boolean fail) {
            this.key = key;
            this.values = values;
            this.fail = fail;
        }

        @Override
        public String key() { return key; }

        @Override
        public boolean supports(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
            return true;
        }

        @Override
        public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
            if (fail) {
                throw new IllegalStateException("backing store unavailable");
            }
            return values;
        }
    }

    RuleContext ruleContext;
    PrincipalContext principal;
    ResourceContext list;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ruleContext = new RuleContext();
        ruleContext.clear();
        List<AccessListResolver> resolvers = List.of(
                new FixedResolver("allowedCodes", List.of("A", "B"), false),
                new FixedResolver("brokenCodes", List.of("A"), true),
                new FixedResolver("nullCodes", null, false));
        Instance<AccessListResolver> instance = mock(Instance.class);
        when(instance.iterator()).thenAnswer(inv -> resolvers.iterator());
        ruleContext.resolvers = instance;

        principal = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(new DataDomain("test.com", "0000000001", "tenant-a", 0, "user@test.com"))
                .withUserId("user@test.com")
                .withRoles(new String[]{"user"})
                .build();
        list = new ResourceContext.Builder()
                .withArea("widget_hub").withFunctionalDomain("widgets").withAction("list")
                .withResourceId("r-1").withOwnerId(principal.getUserId()).build();
    }

    @Test
    void unresolvableVariableYieldsMatchNothingFilter() {
        addRule("missing-var", "code:^[${noSuchVariable}]", RuleEffect.ALLOW);

        List<Filter> filters = ruleContext.getFilters(new ArrayList<>(), principal, list, Widget.class);

        assertEquals(1, filters.size(), "the rule must contribute exactly one (match-nothing) filter, not be dropped");
        assertMatchNothing(filters.get(0));
    }

    @Test
    void throwingResolverYieldsMatchNothingFilter() {
        addRule("broken-resolver", "code:^[${brokenCodes}]", RuleEffect.ALLOW);

        List<Filter> filters = ruleContext.getFilters(new ArrayList<>(), principal, list, Widget.class);

        assertEquals(1, filters.size());
        assertMatchNothing(filters.get(0));
        MorphiaUtilsBundleAssert.failedKeyRecorded(ruleContext, principal, list, "brokenCodes");
    }

    @Test
    void nullResolverValueYieldsMatchNothingFilter() {
        addRule("null-resolver", "code:${nullCodes}", RuleEffect.ALLOW);

        List<Filter> filters = ruleContext.getFilters(new ArrayList<>(), principal, list, Widget.class);

        assertEquals(1, filters.size());
        assertMatchNothing(filters.get(0));
    }

    @Test
    void resolvableVariableStillFilters() {
        addRule("ok-resolver", "code:^[${allowedCodes}]", RuleEffect.ALLOW);

        List<Filter> filters = ruleContext.getFilters(new ArrayList<>(), principal, list, Widget.class);

        assertEquals(1, filters.size());
        Filter in = filters.get(0);
        assertEquals("$in", in.getName());
        assertEquals("code", in.getField());
        assertEquals(List.of("A", "B"), in.getValue());
    }

    @Test
    void inMemoryEvaluatorDoesNotApplyAllowRuleWithUnresolvedVariable() {
        Rule allow = rule("allow-missing", "code:${noSuchVariable}", RuleEffect.ALLOW);
        MatchEvent event = MatchEvent.builder().build();

        Optional<Boolean> result = ruleContext.evaluateFilterApplicability(
                principal, list, allow, Widget.class, Map.of("code", "A"), event);

        assertEquals(Optional.of(false), result, "ALLOW rule must not apply when its filter cannot be resolved");
        assertTrue(event.getFilterReason().contains("noSuchVariable"));
    }

    @Test
    void inMemoryEvaluatorStillAppliesDenyRuleWithUnresolvedVariable() {
        Rule deny = rule("deny-broken", "code:^[${brokenCodes}]", RuleEffect.DENY);

        Optional<Boolean> result = ruleContext.evaluateFilterApplicability(
                principal, list, deny, Widget.class, Map.of("code", "Z"), MatchEvent.builder().build());

        assertEquals(Optional.of(true), result, "DENY rule must still apply when its filter cannot be resolved");
    }

    @Test
    void writeIsDeniedWhenRuleFilterVariableIsUnresolved() {
        addRule("write-missing", "dataDomain.tenantId:${noSuchVariable}", RuleEffect.ALLOW);
        ResourceContext save = new ResourceContext.Builder()
                .withArea("widget_hub").withFunctionalDomain("widgets").withAction("save").build();
        SecurityCheckResponse response = ruleContext.checkRules(principal, save);
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect());

        Widget widget = new Widget();
        widget.setDataDomain(principal.getDataDomain());

        SecurityCheckException ex = assertThrows(SecurityCheckException.class,
                () -> ruleContext.enforceWriteFilters(principal, save, response, widget));
        assertTrue(ex.getMessage().contains("noSuchVariable"), ex.getMessage());
        assertTrue(ex.getMessage().contains("tenant-a"), "message names the data domain: " + ex.getMessage());
    }

    @Test
    void writeIsDeniedWhenFiltersCannotBeEvaluated() {
        // quantum-morphia-repos has no in-memory predicate engine on its classpath; a write that a
        // rule filter constrains must then be denied rather than allowed unchecked.
        addRule("write-tenant", "dataDomain.tenantId:${pTenantId}", RuleEffect.ALLOW);
        ResourceContext save = new ResourceContext.Builder()
                .withArea("widget_hub").withFunctionalDomain("widgets").withAction("save").build();
        SecurityCheckResponse response = ruleContext.checkRules(principal, save);
        Widget widget = new Widget();
        widget.setDataDomain(principal.getDataDomain());

        assertThrows(SecurityCheckException.class,
                () -> ruleContext.enforceWriteFilters(principal, save, response, widget));
    }

    @Test
    void writeWithoutRuleFiltersIsNotConstrained() {
        addRule("write-unfiltered", null, RuleEffect.ALLOW);
        ResourceContext save = new ResourceContext.Builder()
                .withArea("widget_hub").withFunctionalDomain("widgets").withAction("save").build();
        SecurityCheckResponse response = ruleContext.checkRules(principal, save);
        Widget widget = new Widget();
        widget.setDataDomain(new DataDomain("other.com", "0000000009", "tenant-z", 0, "x@other.com"));

        ruleContext.enforceWriteFilters(principal, save, response, widget);
    }

    private void assertMatchNothing(Filter filter) {
        assertEquals("$in", filter.getName());
        assertEquals("_id", filter.getField());
        assertNotNull(filter.getValue());
        assertTrue(((Collection<?>) filter.getValue()).isEmpty(), "match-nothing filter is _id $in []");
    }

    private void addRule(String name, String andFilter, RuleEffect effect) {
        Rule r = rule(name, andFilter, effect);
        ruleContext.addRule(r.getSecurityURI().getHeader(), r);
    }

    private Rule rule(String name, String andFilter, RuleEffect effect) {
        SecurityURIHeader header = new SecurityURIHeader("user", "widget_hub", "widgets", "*");
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withOrgRefName("*").withAccountNumber("*").withRealm("*").withTenantId("*")
                .withOwnerId("*").withDataSegment("*").withResourceId("*").build();
        return new Rule.Builder()
                .withName(name)
                .withSecurityURI(new SecurityURI(header, body))
                .withEffect(effect)
                .withPriority(10)
                .withAndFilterString(andFilter)
                .build();
    }

    /** Small helper so the failed-key assertion reads clearly in the test above. */
    static final class MorphiaUtilsBundleAssert {
        static void failedKeyRecorded(RuleContext ctx, PrincipalContext p, ResourceContext r, String key) {
            var bundle = ctx.resolveVariableBundle(p, r, Widget.class);
            assertTrue(bundle.failedKeys.contains(key), "failed resolver key must be recorded");
            assertFalse(bundle.objects.containsKey(key), "failed resolver key must not carry a value");
        }
    }
}
