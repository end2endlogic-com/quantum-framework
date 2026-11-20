package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.FunctionalAreaRepo;
import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.FunctionalAction;
import com.e2eq.framework.model.security.FunctionalArea;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.DeleteOptions;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * check-with-index is the rule engine's answer, cell for cell: every action in the snapshot is one that
 * {@code /check} allows, and every action {@code /check} allows is in it. A realm policy (not only the
 * system rules) and an application policy decide, and a wildcard action pattern counts. The vocabulary
 * is read under the caller's own rules, so the policy also lets the user see it.
 */
@QuarkusTest
class PermissionSnapshotParityTest extends BaseRepoTest {
    private static final String APP = "snapshot-parity-app";
    private static final String USER = "snapshot-parity-user@test.example";
    private static final String AREA = "SNAP_AREA";
    private static final String DOMAIN = "SNAP_DOC";
    private static final List<String> ACTIONS = List.of("VIEW", "EDIT", "DELETE", "APPROVE");

    @Inject ApplicationRepo applications;
    @Inject FunctionalAreaRepo areas;
    @Inject FunctionalDomainRepo domains;
    @Inject CredentialRepo credentials;
    @Inject MorphiaDataStoreWrapper stores;
    @Inject RealmMembershipService memberships;
    @Inject PolicyRepo policies;
    @Inject RuleContext ruleContext;
    @Inject SecurityUtils securityUtils;

    private String realm;
    private Policy policy;
    private Policy appPolicy;

    @BeforeEach
    void seed() {
        realm = testUtils.getTestRealm();
        try (var ignored = asSystem()) {
            applications.ensureRegistered(APP, securityUtils.getSystemDataDomain());
            String store = domains.getSecurityContextRealmId();
            areas.save(store, area());
            domains.save(store, domain());

            CredentialUserIdPassword credential = new CredentialUserIdPassword();
            credential.setUserId(USER);
            credential.setSubject(USER);
            credential.setRefName(USER);
            credential.setRoles(new String[]{"user"});
            credential.setDataDomain(testUtils.getTestDataDomain());
            credential.setDomainContext(new DomainContext(testUtils.getTestDataDomain(), realm));
            credentials.save(testUtils.getSystemRealm(), credential);
            memberships.upsertUserRealmRole(UserRealmRole.builder()
                    .refName("snapshot-parity:" + USER).userId(USER).realmRefName(realm)
                    .roles(List.of("user")).status(UserRealmRole.STATUS_ACTIVE)
                    .authorizedTenantIds(List.of(testUtils.getTestDataDomain().getTenantId()))
                    .dataDomain(testUtils.getTestDataDomain()).build());
        }
        // A realm policy, which the system-rules-only snapshot used to miss: every action on the domain by
        // pattern, except DELETE, which a final DENY refuses.
        Policy p = new Policy();
        p.setRefName("SNAPSHOT_PARITY_POLICY");
        p.setDisplayName("Snapshot parity policy");
        p.setPrincipalId("user");
        p.setDataDomain(securityUtils.getSystemDataDomain());
        p.getRules().add(rule("snapshot-parity-deny-delete", "delete", RuleEffect.DENY, 10, true));
        p.getRules().add(rule("snapshot-parity-allow-all", "*", RuleEffect.ALLOW, 20, false));
        // Loading one's own snapshot reads the application and its vocabulary under one's own rules.
        p.getRules().add(rule("snapshot-parity-see-app", "security", "application", "view", RuleEffect.ALLOW, 1, true));
        p.getRules().add(rule("snapshot-parity-list-areas", "security", "functional_area", "list", RuleEffect.ALLOW, 1, true));
        p.getRules().add(rule("snapshot-parity-list-domains", "security", "functional_domain", "list", RuleEffect.ALLOW, 1, true));
        // An application policy: APPROVE is refused in this application only.
        Policy a = new Policy();
        a.setRefName("SNAPSHOT_PARITY_APP_POLICY");
        a.setDisplayName("Snapshot parity application policy");
        a.setPrincipalId("user");
        a.setApplicationId(APP);
        a.setDataDomain(securityUtils.getSystemDataDomain());
        a.getRules().add(rule("snapshot-parity-app-deny-approve", "approve", RuleEffect.DENY, 5, true));
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            policy = policies.save(realm, p);
            appPolicy = policies.save(realm, a);
            ruleContext.reloadFromRepo(realm);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            if (policy != null) policies.delete(realm, policy);
            if (appPolicy != null) policies.delete(realm, appPolicy);
            ruleContext.reloadFromRepo(realm);
        }
        String store = domains.getSecurityContextRealmId();
        stores.getDataStore(store).find(FunctionalArea.class).filter(Filters.eq("applicationId", APP))
                .delete(new DeleteOptions().multi(true));
        stores.getDataStore(store).find(FunctionalDomain.class).filter(Filters.eq("applicationId", APP))
                .delete(new DeleteOptions().multi(true));
        var system = stores.getDataStore(testUtils.getSystemRealm());
        system.find(CredentialUserIdPassword.class).filter(Filters.eq("userId", USER)).delete(new DeleteOptions().multi(true));
        system.find(UserRealmRole.class).filter(Filters.eq("userId", USER)).delete(new DeleteOptions().multi(true));
        system.find(Application.class).filter(Filters.eq("refName", APP)).delete(new DeleteOptions().multi(true));
    }

    @Test
    @TestSecurity(user = USER, roles = "user")
    void everySnapshotCellIsTheEnginesAnswer() {
        Response snapshot = post("check-with-index", null);
        assertEquals(200, snapshot.statusCode(), snapshot.asString());
        assertFalse(snapshot.jsonPath().getBoolean("requiresServer"));
        String scope = snapshot.jsonPath().getString("requestedScope");
        assertEquals(scope, scope.toLowerCase(), "scope keys are lower case");
        Map<String, Object> scopes = snapshot.jsonPath().getMap("scopes");
        assertEquals(List.of(scope), new ArrayList<>(scopes.keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Map<String, Map<String, Object>>>> matrix =
                (Map<String, Map<String, Map<String, Map<String, Object>>>>) ((Map<String, Object>) scopes.get(scope)).get("matrix");
        Map<String, Map<String, Object>> cells = matrix.getOrDefault("snap_area", Map.of()).getOrDefault("snap_doc", Map.of());

        for (String action : ACTIONS) {
            Response check = post("check", action);
            assertEquals(200, check.statusCode(), check.asString());
            boolean allowed = "ALLOW".equals(check.jsonPath().getString("finalEffect"));
            Map<String, Object> cell = cells.get(action.toLowerCase());
            assertEquals(allowed, cell != null, action + ": snapshot must agree with /check " + check.asString());
            if (cell != null) assertEquals("ALLOW", cell.get("effect"));
        }
        assertTrue(cells.containsKey("view"));
        assertTrue(cells.containsKey("edit"), "only the realm policy's wildcard allow grants EDIT");
        assertFalse(cells.containsKey("delete"), "the realm policy's final deny wins, as in the engine");
        assertFalse(cells.containsKey("approve"), "the application policy's deny counts");

        cells.values().forEach(cell -> assertEquals("ALLOW", cell.get("effect"), "only allowed actions are returned"));
    }

    @Test
    @TestSecurity(user = USER, roles = "user")
    void unregisteredApplicationIsNamed() {
        Map<String, Object> body = body(null);
        body.put("applicationId", "snapshot-parity-not-registered");
        Response response = given().contentType("application/json").body(body).post("/system/permissions/check-with-index");
        assertEquals(404, response.statusCode(), response.asString());
        assertEquals("APPLICATION_UNKNOWN", response.jsonPath().getString("code"));
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = "system")
    void fdEvaluateIsTheEnginesAnswerEvenWhenAskedForTheIndex() {
        Map<String, Object> body = body(null);
        body.put("area", AREA);
        body.put("functionalDomain", DOMAIN);
        Response evaluated = given().contentType("application/json").queryParam("useIndex", true).body(body)
                .post("/system/permissions/fd/evaluate");
        assertEquals(200, evaluated.statusCode(), evaluated.asString());
        for (String action : ACTIONS) {
            String effect = evaluated.jsonPath().getString("decisions." + AREA + "." + DOMAIN + "." + action + ".effect");
            Response check = post("check", action);
            assertEquals(check.statusCode(), 200, check.asString());
            assertEquals(check.jsonPath().getString("finalEffect"), effect, action + " with useIndex=true is the engine's answer");
        }
    }

    private Response post(String path, String action) {
        return given().contentType("application/json").body(body(action)).post("/system/permissions/" + path);
    }

    private Map<String, Object> body(String action) {
        Map<String, Object> body = new HashMap<>();
        body.put("identity", USER);
        body.put("realm", realm);
        body.put("applicationId", APP);
        if (action != null) {
            body.put("area", AREA);
            body.put("functionalDomain", DOMAIN);
            body.put("action", action);
        }
        return body;
    }

    private Rule rule(String name, String action, RuleEffect effect, int priority, boolean finalRule) {
        return rule(name, "snap_area", "snap_doc", action, effect, priority, finalRule);
    }

    private Rule rule(String name, String area, String domain, String action, RuleEffect effect, int priority, boolean finalRule) {
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity("user").withArea(area).withFunctionalDomain(domain).withAction(action).build();
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withRealm("*").withOrgRefName("*").withAccountNumber("*")
                .withTenantId("*").withOwnerId("*").withDataSegment("*").withResourceId("*").build();
        return new Rule.Builder().withName(name).withSecurityURI(new SecurityURI(header, body))
                .withEffect(effect).withPriority(priority).withFinalRule(finalRule).build();
    }

    private FunctionalArea area() {
        FunctionalArea area = new FunctionalArea();
        area.setRefName(AREA);
        area.setDisplayName(AREA);
        area.setApplicationId(APP);
        area.setDataDomain(securityUtils.getSystemDataDomain());
        return area;
    }

    private FunctionalDomain domain() {
        FunctionalDomain domain = new FunctionalDomain();
        domain.setRefName(DOMAIN);
        domain.setDisplayName(DOMAIN);
        domain.setApplicationId(APP);
        domain.setArea(AREA);
        List<FunctionalAction> list = new ArrayList<>();
        for (String action : ACTIONS) {
            FunctionalAction row = new FunctionalAction();
            row.setRefName(action);
            row.setDisplayName(action);
            list.add(row);
        }
        domain.setFunctionalActions(list);
        domain.setDataDomain(securityUtils.getSystemDataDomain());
        return domain;
    }
}
