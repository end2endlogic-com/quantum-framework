package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoSecurityFilterBuilderTest {

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
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
