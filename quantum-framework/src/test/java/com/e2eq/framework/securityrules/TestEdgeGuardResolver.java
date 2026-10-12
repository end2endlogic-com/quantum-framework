package com.e2eq.framework.tests.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.security.runtime.AccessListResolver;
import com.e2eq.framework.test.EdgeGuardOrder;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Publishes ${edgeGuardRefNames} for orders only, like an application resolver that knows its own
 * business records and not OntologyEdge. Used by OntologyEdgeWriteFilterTest; answers only for
 * principals with an {@code edge-guard-*} role so it cannot affect any other test.
 */
@ApplicationScoped
public class TestEdgeGuardResolver implements AccessListResolver {

    public static final String KEY = "edgeGuardRefNames";
    public static final List<String> ALLOWED = List.of("edge-guard-order-ok");

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public boolean supports(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
        return pctx != null && pctx.getRoles() != null
                && Arrays.stream(pctx.getRoles()).anyMatch(r -> r != null && r.startsWith("edge-guard-"))
                && modelClass != null && EdgeGuardOrder.class.isAssignableFrom(modelClass);
    }

    @Override
    public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
        return ALLOWED;
    }
}
