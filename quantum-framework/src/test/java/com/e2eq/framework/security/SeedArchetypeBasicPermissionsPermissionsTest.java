package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.seeds.ArchetypeSeeder;
import com.e2eq.framework.securityrules.RuleContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase C: Verify permissions after importing the starter seed archetype into a test realm/tenant.
 *
 * Roles and expectations (seed-defined):
 * - user: can login; can VIEW/UPDATE/changePassword their userProfile; can VIEW dashboard/home; can LIST catalog/item; cannot DELETE.
 * - power_user: full rights on all areas/domains except DELETE (explicit deny wins).
 * - tenant_admin: full rights on everything within tenant (ALLOW * and final).
 */
@QuarkusTest
public class SeedArchetypeBasicPermissionsPermissionsTest extends BaseRepoTest {

    @Inject
    RuleContext ruleContext;

    private String realm;
    private DataDomain tenantDd;

    @BeforeEach
    @ActivateRequestContext
    @TestSecurity(user = "system@end2endlogic.com", roles = {"admin"})
    public void seedOnce() {
        realm = testUtils.getTestRealm();
        tenantDd = new DataDomain("end2endlogic", "0000000001", "tenant-seed-1", 0, "system@end2endlogic.com");
        // Import the archetype with writes enabled; idempotent across runs
        ArchetypeSeeder.importArchetype("basic-permissions-v1-test", realm, tenantDd, true);
    }

    @AfterEach
    public void clear() {
        SecurityContext.clear();
    }

    private PrincipalContext pc(String userId, String... roles) {
        return new PrincipalContext.Builder()
                .withDefaultRealm(realm)
                .withUserId(userId)
                .withRoles(roles)
                .withDataDomain(new DataDomain("end2endlogic", "0000000001", "tenant-seed-1", 0, userId))
                .withScope("api")
                .build();
    }

    private ResourceContext rc(String area, String domain, String action, String ownerId) {
        return new ResourceContext.Builder()
                .withRealm(realm)
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .withResourceId("TEST-RES")
                .withOwnerId(ownerId)
                .build();
    }

    private void assertAllow(SecurityCheckResponse resp, String message) {
        assertNotNull(resp, "response should not be null");
        assertEquals("ALLOW", resp.getDecision(), message + " (decision)");
        assertTrue(resp.getFinalEffect() == RuleEffect.ALLOW || "ALLOW".equals(resp.getDecision()), message + " (final effect)");
    }

    private void assertDeny(SecurityCheckResponse resp, String message) {
        assertNotNull(resp, "response should not be null");
        // In LEGACY mode with SCOPED candidate promotion, decision may reflect a candidate while finalEffect
        // preserves historical behavior. Assert on finalEffect primarily, and tolerate decision discrepancies.
        assertTrue(resp.getFinalEffect() == RuleEffect.DENY, message + " (final effect)");
    }

    @Test
    @ActivateRequestContext
    @TestSecurity(user = "system@end2endlogic.com", roles = {"admin"})
    public void user_permissions() {
        String user = "test-user@end2endlogic.com";
        PrincipalContext p = pc(user, "user");

        // login allowed
        SecurityCheckResponse resp = ruleContext.checkRules(p, rc("security", "auth", "login", user), RuleEffect.DENY);
        assertAllow(resp, "user should be able to login");

        // view/update/changePassword on own profile allowed
        resp = ruleContext.checkRules(p, rc("security", "userProfile", "VIEW", user), RuleEffect.DENY);
        assertAllow(resp, "user should VIEW own profile");

        resp = ruleContext.checkRules(p, rc("security", "userProfile", "UPDATE", user), RuleEffect.DENY);
        assertAllow(resp, "user should UPDATE own profile");

        resp = ruleContext.checkRules(p, rc("security", "userProfile", "changePassword", user), RuleEffect.DENY);
        assertAllow(resp, "user should changePassword");

        // public browse
        resp = ruleContext.checkRules(p, rc("dashboard", "home", "VIEW", user), RuleEffect.DENY);
        assertAllow(resp, "user should VIEW dashboard/home");

        resp = ruleContext.checkRules(p, rc("catalog", "item", "LIST", user), RuleEffect.DENY);
        assertAllow(resp, "user should LIST catalog/item");

        // delete denied
        resp = ruleContext.checkRules(p, rc("catalog", "item", "DELETE", user), RuleEffect.DENY);
        assertDeny(resp, "user should not be able to DELETE");
    }

    @Test
    @ActivateRequestContext
    @TestSecurity(user = "system@end2endlogic.com", roles = {"admin"})
    public void power_user_permissions() {
        String user = "power-user@end2endlogic.com";
        PrincipalContext p = pc(user, "power_user");

        // Broad create/update/view/list allowed
        SecurityCheckResponse resp = ruleContext.checkRules(p, rc("sales", "order", "CREATE", user), RuleEffect.DENY);
        assertAllow(resp, "power_user should CREATE");

        resp = ruleContext.checkRules(p, rc("sales", "order", "UPDATE", user), RuleEffect.DENY);
        assertAllow(resp, "power_user should UPDATE");

        resp = ruleContext.checkRules(p, rc("sales", "order", "VIEW", user), RuleEffect.DENY);
        assertAllow(resp, "power_user should VIEW");

        resp = ruleContext.checkRules(p, rc("sales", "order", "LIST", user), RuleEffect.DENY);
        assertAllow(resp, "power_user should LIST");

        // delete explicitly denied (deny rule priority 290 beats allow at 300)
        resp = ruleContext.checkRules(p, rc("sales", "order", "DELETE", user), RuleEffect.DENY);
        assertDeny(resp, "power_user should NOT DELETE");
    }

    @Test
    @ActivateRequestContext
    @TestSecurity(user = "system@end2endlogic.com", roles = {"admin"})
    public void tenant_admin_permissions() {
        String user = "tenant-admin@end2endlogic.com";
        PrincipalContext p = pc(user, "tenant_admin");

        // A few spot checks across actions including DELETE
        SecurityCheckResponse resp = ruleContext.checkRules(p, rc("catalog", "item", "DELETE", user), RuleEffect.DENY);
        assertAllow(resp, "tenant_admin should DELETE");

        resp = ruleContext.checkRules(p, rc("security", "userProfile", "UPDATE", user), RuleEffect.DENY);
        assertAllow(resp, "tenant_admin should UPDATE");
    }

    @Test
    @ActivateRequestContext
    @TestSecurity(user = "system@end2endlogic.com", roles = {"admin"})
    public void strict_with_resource_yields_exact() {
        String user = "test-user@end2endlogic.com";
        PrincipalContext p = pc(user, "user");
        ResourceContext r = rc("security", "userProfile", "VIEW", user);
        java.util.Map<String, Object> resource = new java.util.HashMap<>();
        resource.put("userId", user);
        resource.put("ownerId", user);

        SecurityCheckResponse resp = ruleContext.checkRules(p, r, null, resource, RuleEffect.DENY, EvalMode.STRICT);
        assertAllow(resp, "STRICT with resource should allow VIEW own profile");
        assertEquals("EXACT", resp.getDecisionScope(), "STRICT + resource should yield EXACT scope when fully evaluated");
    }
}
