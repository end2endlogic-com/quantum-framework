package com.e2eq.framework;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.test.MongoDbInitResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MongoDB-backed parity test: persist policies directly (no YAML), reload RuleContext from repo,
 * and assert identical behavior to the YAML-driven roles/no-match scenario.
 */
@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
public class MongoPolicyParityIT {

    @Inject
    PolicyRepo policyRepo;

    @Inject
    RuleContext ruleContext;

    private static final String REALM = "parity-realm"; // isolated realm for this test

    private PrincipalContext admin;
    private PrincipalContext user;

    @BeforeEach
    void initPrincipals() {
        DataDomain dd = new DataDomain("end2endlogic.com", "0000000001", "end2endlogic.com", 0, "principal@end2endlogic.com");
        admin = new PrincipalContext.Builder()
                .withDefaultRealm(REALM)
                .withDataDomain(dd)
                .withUserId("admin@end2endlogic.com")
                .withRoles(new String[]{"admin"})
                .build();

        user = new PrincipalContext.Builder()
                .withDefaultRealm(REALM)
                .withDataDomain(dd)
                .withUserId("user@end2endlogic.com")
                .withRoles(new String[]{"user"})
                .build();
    }

    @Test
    void policies_persisted_in_mongo_produce_same_outcomes_as_yaml_roles_scenario() {
        // Build a Policy for principal role "admin" mirroring rules_roles_and_no_match.yaml
        Policy adminPolicy = new Policy();
        adminPolicy.setPrincipalType(Policy.PrincipalType.ROLE);
        adminPolicy.setPrincipalId("admin");
        adminPolicy.setDescription("Mongo parity: admin role rules");

        // Rule 1: allow finance/ledger view
        Rule allowFinanceLedgerView = new Rule.Builder()
                .withName("allow-admin-finance-ledger-view")
                .withSecurityURI(new SecurityURI(
                        new SecurityURIHeader.Builder()
                                .withIdentity("admin")
                                .withArea("finance")
                                .withFunctionalDomain("ledger")
                                .withAction("view")
                                .build(),
                        defaultBody()))
                .withEffect(RuleEffect.ALLOW)
                .withPriority(10)
                .withFinalRule(false)
                .build();

        // Rule 2: allow security/userProfile list
        Rule allowUserProfileList = new Rule.Builder()
                .withName("allow-admin-userprofile-list")
                .withSecurityURI(new SecurityURI(
                        new SecurityURIHeader.Builder()
                                .withIdentity("admin")
                                .withArea("security")
                                .withFunctionalDomain("userProfile")
                                .withAction("list")
                                .build(),
                        defaultBody()))
                .withEffect(RuleEffect.ALLOW)
                .withPriority(9)
                .withFinalRule(false)
                .build();

        adminPolicy.getRules().add(allowFinanceLedgerView);
        adminPolicy.getRules().add(allowUserProfileList);

        // Persist to Mongo under the test realm
        policyRepo.save(REALM, adminPolicy);

        // Hydrate RuleContext from the repository and evaluate
        ruleContext.reloadFromRepo(REALM);

        // Admin allowed according to rules
        assertAllowed(admin, "finance", "ledger", "view");
        assertAllowed(admin, "security", "userProfile", "list");

        // User (no rules) should be denied on the same checks
        assertDenied(user, "finance", "ledger", "view");
        assertDenied(user, "security", "userProfile", "list");
        // And other unmatched area/domain/action is DENY
        assertDenied(user, "orders", "order", "delete");
    }

    private static SecurityURIBody defaultBody() {
        return new SecurityURIBody.Builder()
                .withOrgRefName("*")
                .withAccountNumber("*")
                .withRealm("*")
                .withTenantId("*")
                .withOwnerId("*")
                .withDataSegment("*")
                .withResourceId("*")
                .build();
    }

    private void assertAllowed(PrincipalContext pctx, String area, String domain, String action) {
        SecurityCheckResponse resp = check(pctx, area, domain, action);
        assertEquals(RuleEffect.ALLOW, resp.getFinalEffect(), () -> pctx.getUserId() + " expected ALLOW for " + area + "/" + domain + "/" + action);
    }

    private void assertDenied(PrincipalContext pctx, String area, String domain, String action) {
        SecurityCheckResponse resp = check(pctx, area, domain, action);
        assertEquals(RuleEffect.DENY, resp.getFinalEffect(), () -> pctx.getUserId() + " expected DENY for " + area + "/" + domain + "/" + action);
    }

    private SecurityCheckResponse check(PrincipalContext pctx, String area, String domain, String action) {
        ResourceContext rc = new ResourceContext.Builder()
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .withResourceId("res-mongo")
                .withOwnerId(pctx.getUserId())
                .build();
        return ruleContext.checkRules(pctx, rc);
    }
}
