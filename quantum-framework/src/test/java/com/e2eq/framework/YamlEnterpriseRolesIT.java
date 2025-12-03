package com.e2eq.framework;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.io.YamlRuleLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * YAML-authored enterprise roles matrix tests.
 * Roles covered: admin, hr_manager, readonly_ops, orgs_jobs_mgr, tenant_admin (scoped to realm "test-security").
 */
public class YamlEnterpriseRolesIT {

    private final YamlRuleLoader loader = new YamlRuleLoader();
    private RuleContext ruleContext;

    private PrincipalContext admin;
    private PrincipalContext hrManager;
    private PrincipalContext readonlyOps;
    private PrincipalContext orgsJobsMgr;
    private PrincipalContext tenantAdminInScope;
    private PrincipalContext tenantAdminOutOfScope;
    private PrincipalContext noRoleUser;

    @BeforeEach
    void setup() {
        ruleContext = new RuleContext();
        ruleContext.clear();

        DataDomain dd = new DataDomain("end2endlogic.com", "0000000001", "end2endlogic.com", 0, "user@end2endlogic.com");

        admin = principal("system-com", dd, "admin");
        hrManager = principal("system-com", dd, "hr_manager");
        readonlyOps = principal("system-com", dd, "readonly_ops");
        orgsJobsMgr = principal("system-com", dd, "orgs_jobs_mgr");
        tenantAdminInScope = principal("test-security", dd, "tenant_admin");
        tenantAdminOutOfScope = principal("other-realm", dd, "tenant_admin");
        noRoleUser = new PrincipalContext.Builder()
                .withDefaultRealm("system-com")
                .withDataDomain(dd)
                .withUserId("norole@end2endlogic.com")
                .withRoles(new String[]{})
                .build();
    }

    @Test
    void enterprise_roles_behave_as_expected() throws Exception {
        try (InputStream is = res("/rules_enterprise_roles.yaml")) {
            List<Rule> rules = loader.load(is);
            assertTrue(rules.size() >= 5, "Expected at least 5 expanded rules");

            for (Rule r : rules) {
                ruleContext.addRule(r.getSecurityURI().getHeader(), r);
            }

            // --- admin: full access everywhere ---
            assertAllowed(admin, "SECURITY", "POLICY", "VIEW");
            assertAllowed(admin, "WORK_HUB", "JOBS", "CREATE");
            assertAllowed(admin, "INTEGRATION", "EXPORT_JOBS", "UPDATE");
            assertAllowed(admin, "LOCATION_HUB", "LOCATIONS", "DELETE");

            // --- hr_manager: areas PEOPLE_HUB, LocationHub, JobHub ---
            assertAllowed(hrManager, "PEOPLE_HUB", "ASSOCIATES", "UPDATE");
            assertAllowed(hrManager, "LocationHub", "LOCATION_LIST", "LIST");
            assertAllowed(hrManager, "JobHub", "JOB", "VIEW");
            assertDenied(hrManager, "SECURITY", "POLICY", "UPDATE");
            assertDenied(hrManager, "SYSTEM", "DYNAMIC_ATTRIBUTE_SET", "DELETE");

            // --- readonly_ops: VIEW/LIST only on operational areas ---
            assertAllowed(readonlyOps, "WORK_HUB", "JOBS", "VIEW");
            assertAllowed(readonlyOps, "INTEGRATION", "EXPORT_JOBS", "LIST");
            assertAllowed(readonlyOps, "SYSTEM", "COUNTER", "VIEW");
            assertAllowed(readonlyOps, "LOCATION_HUB", "LOCATIONS", "LIST");
            assertDenied(readonlyOps, "WORK_HUB", "JOBS", "CREATE");
            assertDenied(readonlyOps, "INTEGRATION", "EXPORT_JOBS", "UPDATE");
            assertDenied(readonlyOps, "SYSTEM", "COUNTER", "DELETE");

            // --- orgs_jobs_mgr: manage specific domains ---
            assertAllowed(orgsJobsMgr, "PEOPLE_HUB", "ORGANIZATIONS", "CREATE");
            assertAllowed(orgsJobsMgr, "PEOPLE_HUB", "ASSOCIATES", "UPDATE");
            assertAllowed(orgsJobsMgr, "JobHub", "JOB", "DELETE");
            assertAllowed(orgsJobsMgr, "JobHub", "JOB_Plan", "ARCHIVE");
            assertDenied(orgsJobsMgr, "SECURITY", "USER_PROFILE", "UPDATE");

            // --- tenant_admin scoped by realm in body ---
            assertAllowed(tenantAdminInScope, "WORKLOG", "WORKLOG_ENTRIES", "CREATE");
            assertAllowed(tenantAdminInScope, "INTEGRATION", "EXPORT_REQUESTS", "VIEW");
            // Out of scope (different realm) should be denied for the same checks
            assertDenied(tenantAdminOutOfScope, "WORKLOG", "WORKLOG_ENTRIES", "CREATE");
            assertDenied(tenantAdminOutOfScope, "INTEGRATION", "EXPORT_REQUESTS", "VIEW");

            // --- No role user: default deny on samples ---
            assertDenied(noRoleUser, "PEOPLE_HUB", "ASSOCIATES", "VIEW");
            assertDenied(noRoleUser, "WORK_HUB", "JOBS", "LIST");
        }
    }

    private PrincipalContext principal(String realm, DataDomain dd, String role) {
        return new PrincipalContext.Builder()
                .withDefaultRealm(realm)
                .withDataDomain(dd)
                .withUserId(role + "@end2endlogic.com")
                .withRoles(new String[]{role})
                .build();
    }

    private void assertAllowed(PrincipalContext pctx, String area, String domain, String action) {
        SecurityCheckResponse resp = check(pctx, area, domain, action);
        assertEquals(RuleEffect.ALLOW, resp.getFinalEffect(), () -> pctx.getUserId() + " -> ALLOW expected for " + area + "/" + domain + "/" + action);
    }

    private void assertDenied(PrincipalContext pctx, String area, String domain, String action) {
        SecurityCheckResponse resp = check(pctx, area, domain, action);
        assertEquals(RuleEffect.DENY, resp.getFinalEffect(), () -> pctx.getUserId() + " -> DENY expected for " + area + "/" + domain + "/" + action);
    }

    private SecurityCheckResponse check(PrincipalContext pctx, String area, String domain, String action) {
        ResourceContext rc = new ResourceContext.Builder()
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .withResourceId("res-enterprise")
                .withOwnerId(pctx.getUserId())
                .build();
        return ruleContext.checkRules(pctx, rc);
    }

    private static InputStream res(String path) {
        InputStream is = YamlEnterpriseRolesIT.class.getResourceAsStream(path);
        assertNotNull(is, "Missing resource: " + path);
        return is;
    }
}
