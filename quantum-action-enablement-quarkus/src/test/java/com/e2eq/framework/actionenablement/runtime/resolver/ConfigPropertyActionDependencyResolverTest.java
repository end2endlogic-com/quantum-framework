package com.e2eq.framework.actionenablement.runtime.resolver;

import com.e2eq.framework.actionenablement.model.DependencyCheckRef;
import com.e2eq.framework.actionenablement.model.EnablementImpact;
import com.e2eq.framework.actionenablement.runtime.DependencyResolutionResult;
import com.e2eq.framework.actionenablement.runtime.EnablementEvaluationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConfigPropertyActionDependencyResolverTest {

    private final ConfigPropertyActionDependencyResolver resolver = new ConfigPropertyActionDependencyResolver();

    @Test
    void missingPropertyNameProducesReadyBlocker() {
        DependencyResolutionResult result = resolver.evaluate(
                DependencyCheckRef.builder().type("setting-present").build(),
                EnablementEvaluationContext.builder().realm("test").build()
        );

        assertFalse(result.isSatisfied());
        assertEquals(1, result.getBlockers().size());
        assertEquals(EnablementImpact.READY, result.getBlockers().get(0).getImpact());
        assertEquals("setting-name-missing", result.getBlockers().get(0).getCode());
    }
}
