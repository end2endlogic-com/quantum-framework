package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.securityrules.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoSecurityFilterBuilderFieldPolicyTest {

    @AfterEach
    void clearSecurityState() {
        SecurityContext.clear();
        while (SecurityContext.isIgnoringRules()) {
            SecurityContext.exitIgnoreRulesMode();
        }
    }

    @Test
    void missingPrincipalContextFailsClosed() {
        RepoSecurityContextResolver resolver =
                new RepoSecurityContextResolver(null, null, null, null, "test-realm");
        RepoSecurityFilterBuilder builder = new RepoSecurityFilterBuilder(resolver, null);

        assertThrows(IllegalStateException.class, builder::buildExcludedFieldPaths);
    }

    @Test
    void explicitIgnoreRulesScopeBypassesFieldProjection() {
        RepoSecurityFilterBuilder builder = new RepoSecurityFilterBuilder(null, null);

        SecurityContext.enterIgnoreRulesMode();

        assertTrue(builder.buildExcludedFieldPaths().isEmpty());
    }
}
