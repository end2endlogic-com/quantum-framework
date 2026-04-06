package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.MatchEvent;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleFilterApplicabilityEvaluatorTest {

    @Test
    void returnsEmptyWhenPredicateCompilationFails() {
        Rule rule = new Rule();
        rule.setName("bad-filter");
        rule.setAndFilterString("text(\"one\")&&text(\"two\")");

        MatchEvent matchEvent = MatchEvent.builder().build();
        Optional<Boolean> result = new RuleFilterApplicabilityEvaluator(new RuleVariableBundleResolver(null))
                .evaluate(principalContext(), resourceContext(), rule, TestModel.class, Map.of("status", "OPEN"), matchEvent);

        assertTrue(result.isEmpty());
        assertFalse(matchEvent.isFilterEvaluated());
        assertEquals("Predicate compilation unavailable", matchEvent.getFilterReason());
    }

    private PrincipalContext principalContext() {
        return new PrincipalContext.Builder()
                .withDefaultRealm("system-com")
                .withDataDomain(new DataDomain("org", "acct", "tenant", 0, "alice@example.com"))
                .withUserId("alice@example.com")
                .withRoles(new String[]{"admin"})
                .withScope("access")
                .build();
    }

    private ResourceContext resourceContext() {
        return new ResourceContext.Builder()
                .withRealm("system-com")
                .withArea("ops")
                .withFunctionalDomain("orders")
                .withAction("view")
                .withResourceId("order-1")
                .withOwnerId("alice@example.com")
                .build();
    }

    static class TestModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() {
            return "ops";
        }

        @Override
        public String bmFunctionalDomain() {
            return "orders";
        }
    }
}
