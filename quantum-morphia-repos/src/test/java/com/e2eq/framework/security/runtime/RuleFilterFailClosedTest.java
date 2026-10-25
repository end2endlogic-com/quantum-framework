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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

    /** The in-memory predicate engine RuleWriteFilterGuard looks up by name (quantum-query-runtime). */
    private static final String QUERY_PREDICATES = "com.e2eq.framework.query.runtime.QueryPredicates";

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
    void inMemoryEvaluatorIsOnTheClasspath() {
        // quantum-query-runtime is a dependency of quantum-morphia-repos (since #117), so the write
        // filter tests below really evaluate the filter rather than hitting the "not available" path.
        assertDoesNotThrow(() -> Class.forName(QUERY_PREDICATES));
    }

    @Test
    void writeIntoAnotherTenantsDataDomainIsDenied() {
        addRule("write-tenant", "dataDomain.tenantId:${pTenantId}", RuleEffect.ALLOW);
        ResourceContext save = saveContext();
        SecurityCheckResponse response = ruleContext.checkRules(principal, save);
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect());
        Widget widget = new Widget();
        widget.setDataDomain(new DataDomain("test.com", "0000000001", "tenant-b", 0, "user@test.com"));

        SecurityCheckException ex = assertThrows(SecurityCheckException.class,
                () -> ruleContext.enforceWriteFilters(principal, save, response, widget));
        assertTrue(ex.getMessage().contains("does not satisfy the rule filter"), ex.getMessage());
        assertTrue(ex.getMessage().contains("write-tenant"), "message names the rule: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("tenant-b"), "message names the data domain: " + ex.getMessage());
        assertEquals(RuleEffect.DENY, response.getFinalEffect());
    }

    @Test
    void writeIntoOwnDataDomainIsAllowed() {
        addRule("write-tenant", "dataDomain.tenantId:${pTenantId}", RuleEffect.ALLOW);
        ResourceContext save = saveContext();
        SecurityCheckResponse response = ruleContext.checkRules(principal, save);
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect());
        Widget widget = new Widget();
        widget.setDataDomain(principal.getDataDomain());

        assertDoesNotThrow(() -> ruleContext.enforceWriteFilters(principal, save, response, widget));
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect());
    }

    @Test
    void writeIsDeniedWhenInMemoryEvaluatorIsAbsent() throws Exception {
        // RuleWriteFilterGuard finds the evaluator with Class.forName on its own class loader and has
        // no injectable seam, so its absence is simulated by loading this package in a class loader
        // that hides com.e2eq.framework.query.runtime. A write the filter would otherwise allow (own
        // tenant) must then be denied rather than allowed unchecked.
        ResourceContext save = saveContext();
        SecurityCheckResponse response = new SecurityCheckResponse();
        response.setFinalEffect(RuleEffect.ALLOW);
        List<SecurityCheckResponse.RuleFilterInfo> filterInfos = List.of(new SecurityCheckResponse.RuleFilterInfo(
                "write-tenant", "dataDomain.tenantId:${pTenantId}", null, "AND"));
        Widget widget = new Widget();
        widget.setDataDomain(principal.getDataDomain());

        try (EvaluatorHidingClassLoader loader = new EvaluatorHidingClassLoader()) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(QUERY_PREDICATES, false, loader));
            Class<?> resolverClass = loader.loadClass(RuleVariableBundleResolver.class.getName());
            Class<?> guardClass = loader.loadClass(RuleWriteFilterGuard.class.getName());
            assertNotSame(RuleWriteFilterGuard.class, guardClass, "the guard must come from the hiding loader");

            Constructor<?> resolverCtor = resolverClass.getDeclaredConstructor(Instance.class, Instance.class);
            resolverCtor.setAccessible(true);
            Object variableResolver = resolverCtor.newInstance(null, null);
            Constructor<?> guardCtor = guardClass.getDeclaredConstructor(resolverClass);
            guardCtor.setAccessible(true);
            Object guard = guardCtor.newInstance(variableResolver);
            Method enforce = guardClass.getDeclaredMethod("enforce", PrincipalContext.class, ResourceContext.class,
                    SecurityCheckResponse.class, List.class, UnversionedBaseModel.class);
            enforce.setAccessible(true);

            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> enforce.invoke(guard, principal, save, response, filterInfos, widget));
            SecurityCheckException ex = assertInstanceOf(SecurityCheckException.class, thrown.getCause());
            assertTrue(ex.getMessage().contains(QUERY_PREDICATES + ") is not available"), ex.getMessage());
            assertTrue(ex.getMessage().contains("tenant-a"), "message names the data domain: " + ex.getMessage());
            assertEquals(RuleEffect.DENY, response.getFinalEffect());
        }
    }

    /**
     * Loads com.e2eq.framework.security.runtime child-first from this module's main and test
     * output, so package-private access between the guard and its collaborators still works, and
     * refuses com.e2eq.framework.query.runtime. Everything else delegates to the test class loader.
     */
    static final class EvaluatorHidingClassLoader extends URLClassLoader {
        private static final String ISOLATED_PACKAGE = "com.e2eq.framework.security.runtime.";
        private static final String HIDDEN_PACKAGE = "com.e2eq.framework.query.runtime.";

        EvaluatorHidingClassLoader() throws URISyntaxException, IOException {
            super(new URL[]{location(RuleWriteFilterGuard.class), location(RuleFilterFailClosedTest.class)},
                    RuleFilterFailClosedTest.class.getClassLoader());
        }

        private static URL location(Class<?> type) throws URISyntaxException, IOException {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toUri().toURL();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(HIDDEN_PACKAGE)) {
                throw new ClassNotFoundException(name + " is hidden by " + getClass().getSimpleName());
            }
            if (name.startsWith(ISOLATED_PACKAGE)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
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

    private static ResourceContext saveContext() {
        return new ResourceContext.Builder()
                .withArea("widget_hub").withFunctionalDomain("widgets").withAction("save").build();
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
