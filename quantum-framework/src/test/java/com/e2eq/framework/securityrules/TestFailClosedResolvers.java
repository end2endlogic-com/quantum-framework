package com.e2eq.framework.tests.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.security.runtime.AccessListResolver;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Resolvers used by RuleFilterFailClosedAndWriteDomainTest. They only answer for principals that
 * carry a {@code rwfc-*} role so they cannot affect any other test.
 */
public final class TestFailClosedResolvers {

    public static final List<String> ALLOWED_TITLES = List.of("rwfc-alpha", "rwfc-beta");

    private TestFailClosedResolvers() {
    }

    static boolean forFailClosedTests(PrincipalContext pctx) {
        return pctx != null && pctx.getRoles() != null
                && Arrays.stream(pctx.getRoles()).anyMatch(r -> r != null && r.startsWith("rwfc-"));
    }

    /** Publishes ${rwfcTitles}. */
    @ApplicationScoped
    public static class AllowedTitles implements AccessListResolver {
        @Override
        public String key() { return "rwfcTitles"; }

        @Override
        public boolean supports(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
            return forFailClosedTests(pctx);
        }

        @Override
        public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
            return ALLOWED_TITLES;
        }
    }

    /** Publishes ${rwfcBroken}, but its backing lookup always throws. */
    @ApplicationScoped
    public static class BrokenTitles implements AccessListResolver {
        @Override
        public String key() { return "rwfcBroken"; }

        @Override
        public boolean supports(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
            return forFailClosedTests(pctx);
        }

        @Override
        public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
            throw new IllegalStateException("simulated access-list backend failure");
        }
    }
}
