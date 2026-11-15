package com.e2eq.framework.service.access;

import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The binding and install authority suite again, with application-scoped policy evaluation required
 * ({@code quantum.security.policy-scope.require-application=true}, as Quantum Auth runs). Callers carry
 * the application they act in; the service's own writes raise to the system principal and keep it.
 */
@QuarkusTest
@TestProfile(ApplicationAccessRequireApplicationTest.RequireApplicationProfile.class)
class ApplicationAccessRequireApplicationTest extends ApplicationAccessAuthorityTest {

    static final String CALLING_APPLICATION = "access-console";

    public static class RequireApplicationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quantum.access-invites.membership-admin-roles", MEMBERSHIP_ADMIN,
                "quantum.security.policy-scope.require-application", "true",
                "quantum.migration.application-id", CALLING_APPLICATION);
        }
    }

    @Override
    PrincipalContext signedIn(String userId, String tenant) {
        return withApplication(super.signedIn(userId, tenant));
    }

    @Override
    <T> T asSystem(Supplier<T> action) {
        return SecurityCallScope.runWithContexts(withApplication(securityUtils.getSystemPrincipalContext()),
            SecurityCallScope.resource(withApplication(securityUtils.getSystemPrincipalContext()), null,
                securityUtils.getSystemSecurityResourceContext().getArea(),
                securityUtils.getSystemSecurityResourceContext().getFunctionalDomain(),
                securityUtils.getSystemSecurityResourceContext().getAction()),
            action);
    }

    @Test
    void aRequestWithoutAnApplicationIsRefusedWithATypedCode() {
        String admin = member("scope-admin", List.of(TENANT_A), List.of("admin"));
        PrincipalContext noApplication = super.signedIn(admin, TENANT_A);
        refused(Code.APPLICATION_SCOPE_REQUIRED, () -> as(noApplication,
            () -> accessService.install(POOLED, install(null, null, TENANT_A))));
    }

    private static PrincipalContext withApplication(PrincipalContext base) {
        return new PrincipalContext.Builder()
            .withDefaultRealm(base.getDefaultRealm())
            .withApplicationId(CALLING_APPLICATION)
            .withDomainContext(base.getDomainContext())
            .withDataDomain(base.getDataDomain())
            .withUserId(base.getUserId())
            .withRoles(base.getRoles())
            .withScope(base.getScope())
            .build();
    }
}
