package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ApplicationScopedSecurityContextTest {

    @Test
    void principalAndResourceCarryApplicationScope() {
        DataDomain dataDomain = new DataDomain("acme", "1001", "acme", 0, "alice");
        PrincipalContext principal = new PrincipalContext.Builder()
                .withDefaultRealm("acme-com")
                .withApplicationId("scheduler")
                .withDataDomain(dataDomain)
                .withUserId("alice")
                .withRoles(new String[]{"scheduler-user"})
                .withScope("AUTHENTICATED")
                .build();
        ResourceContext resource = new ResourceContext.Builder()
                .withRealm("acme-com")
                .withApplicationId("Scheduler")
                .withArea("workforce")
                .withFunctionalDomain("shift")
                .withAction("view")
                .build();

        assertEquals("scheduler", principal.getApplicationId());
        assertEquals("scheduler", resource.getApplicationId());
        assertEquals("scheduler", resource.withDataDomain(dataDomain).getApplicationId());
    }

    @Test
    void applicationParticipatesInContextEquality() {
        DataDomain dataDomain = new DataDomain("acme", "1001", "acme", 0, "alice");
        PrincipalContext scheduler = principal(dataDomain, "scheduler");
        PrincipalContext reporting = principal(dataDomain, "reporting");

        assertNotEquals(scheduler, reporting);
    }

    @Test
    void applicationScopedAnonymousPrincipalPropagatesToResources() {
        DataDomain dataDomain = new DataDomain("system", "0001", "system", 0, "system");
        PrincipalContext principal = SecurityCallScope.anonymous(
                "quantum-auth", dataDomain, "anonymous-registration", "quantum-auth");

        ResourceContext resource = SecurityCallScope.resource(
                principal, null, "SECURITY", "APPLICATION_REGISTRATION", "CREATE");

        assertEquals("quantum-auth", principal.getApplicationId());
        assertEquals("quantum-auth", resource.getApplicationId());
    }

    private static PrincipalContext principal(DataDomain dataDomain, String applicationId) {
        return new PrincipalContext.Builder()
                .withDefaultRealm("acme-com")
                .withApplicationId(applicationId)
                .withDataDomain(dataDomain)
                .withUserId("alice")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .build();
    }
}
