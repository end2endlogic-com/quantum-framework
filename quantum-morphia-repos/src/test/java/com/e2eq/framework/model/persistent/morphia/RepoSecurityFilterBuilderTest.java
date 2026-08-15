package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class RepoSecurityFilterBuilderTest {

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
        while (SecurityContext.isIgnoringRules()) {
            SecurityContext.exitIgnoreRulesMode();
        }
    }

    @Test
    void reportsWhichSecurityContextsAreMissing() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> new RepoSecurityFilterBuilder(
                        new RepoSecurityContextResolver(null, null, null, null, "system-com"),
                        null)
                        .buildSecuredFilters(List.of(), TestModel.class));

        assertTrue(ex.getMessage().contains("PrincipalContext"));
    }

    @Test
    void explicitIgnoreRulesScopeBypassesRowFiltersWithoutSecurityContext() {
        RepoSecurityFilterBuilder builder = new RepoSecurityFilterBuilder(null, null);
        List<Filter> filters = List.of();

        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            assertSame(filters, builder.buildSecuredFilters(filters, TestModel.class));
        }
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
