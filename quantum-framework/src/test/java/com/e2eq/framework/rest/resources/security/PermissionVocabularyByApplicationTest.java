package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.FunctionalAreaRepo;
import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.FunctionalAction;
import com.e2eq.framework.model.security.FunctionalArea;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.persistent.BaseRepoTest;
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
 * A3: an application's registered vocabulary is readable on its own ({@code /fd?applicationId=},
 * {@code /fd/areas?applicationId=}); {@code /fd} without the parameter is unchanged.
 * A1: permission checks resolve user-group roles for the application asked about.
 */
@QuarkusTest
class PermissionVocabularyByApplicationTest extends BaseRepoTest {
    private static final String APP_A = "vocab-read-app-a";
    private static final String APP_B = "vocab-read-app-b";
    private static final String USER = "vocab-read-user@test.example";
    private static final String GROUP_A = "VOCAB_READ_GROUP_A";
    private static final String GROUP_B = "VOCAB_READ_GROUP_B";

    @Inject ApplicationRepo applications;
    @Inject FunctionalAreaRepo areas;
    @Inject FunctionalDomainRepo domains;
    @Inject CredentialRepo credentials;
    @Inject MorphiaDataStoreWrapper stores;
    @Inject SecurityUtils securityUtils;

    @BeforeEach
    void seed() {
        try (var ignored = asSystem()) {
            applications.ensureRegistered(APP_A, securityUtils.getSystemDataDomain());
            applications.ensureRegistered(APP_B, securityUtils.getSystemDataDomain());
            String store = domains.getSecurityContextRealmId();
            areas.save(store, area(APP_A, "VOCAB_PLANNING"));
            areas.save(store, area(APP_A, "VOCAB_EMPTY"));
            areas.save(store, area(APP_B, "VOCAB_BILLING"));
            domains.save(store, domain(APP_A, "VOCAB_PLANNING", "VOCAB_PLAN", "APPROVE", "VIEW"));
            domains.save(store, domain(APP_B, "VOCAB_BILLING", "VOCAB_INVOICE", "SEND"));

            String system = testUtils.getSystemRealm();
            CredentialUserIdPassword credential = new CredentialUserIdPassword();
            credential.setUserId(USER);
            credential.setSubject(USER);
            credential.setRefName(USER);
            credential.setRoles(new String[]{"user"});
            credential.setDataDomain(testUtils.getTestDataDomain());
            credential.setDomainContext(new DomainContext(testUtils.getTestDataDomain(), testUtils.getTestRealm()));
            credentials.save(system, credential);

            UserProfile profile = new UserProfile();
            profile.setRefName(USER);
            profile.setUserId(USER);
            profile.setEmail(USER);
            profile.setDisplayName(USER);
            profile.setDataDomain(testUtils.getTestDataDomain());
            EntityReference credentialRef = new EntityReference();
            credentialRef.setEntityRefName(USER);
            credentialRef.setEntityDisplayName(USER);
            credentialRef.setEntityType("CredentialUserIdPassword");
            profile.setCredentialUserIdPasswordRef(credentialRef);
            stores.getDataStore(system).save(profile);
            stores.getDataStore(system).save(group(GROUP_A, APP_A, "vocab-a-role", profile));
            stores.getDataStore(system).save(group(GROUP_B, APP_B, "vocab-b-role", profile));
        }
    }

    @AfterEach
    void cleanup() {
        String store = domains.getSecurityContextRealmId();
        stores.getDataStore(store).find(FunctionalArea.class).filter(Filters.in("applicationId", List.of(APP_A, APP_B)))
                .delete(new DeleteOptions().multi(true));
        stores.getDataStore(store).find(FunctionalDomain.class).filter(Filters.in("applicationId", List.of(APP_A, APP_B)))
                .delete(new DeleteOptions().multi(true));
        var system = stores.getDataStore(testUtils.getSystemRealm());
        system.find(CredentialUserIdPassword.class).filter(Filters.eq("userId", USER)).delete(new DeleteOptions().multi(true));
        system.find(UserProfile.class).filter(Filters.eq("refName", USER)).delete(new DeleteOptions().multi(true));
        system.find(UserGroup.class).filter(Filters.in("refName", List.of(GROUP_A, GROUP_B))).delete(new DeleteOptions().multi(true));
        system.find(Application.class).filter(Filters.in("refName", List.of(APP_A, APP_B))).delete(new DeleteOptions().multi(true));
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = "system")
    void fdForAnApplicationHoldsOnlyItsRegisteredAreasDomainsAndActions() {
        Response names = given().queryParam("applicationId", APP_A).get("/system/permissions/fd");
        assertEquals(200, names.statusCode(), names.asString());
        Map<String, Object> byArea = names.jsonPath().getMap("");
        assertEquals(Map.of("VOCAB_PLANNING", List.of("VOCAB_PLAN"), "VOCAB_EMPTY", List.of()), byArea,
                "own areas only, an area without domains included, no discovered or foreign vocabulary");

        Response actions = given().queryParam("applicationId", APP_A).queryParam("includeActions", true)
                .get("/system/permissions/fd");
        assertEquals(200, actions.statusCode(), actions.asString());
        assertEquals(List.of("APPROVE", "VIEW"), actions.jsonPath().getList("VOCAB_PLANNING.VOCAB_PLAN"));

        Response other = given().queryParam("applicationId", APP_B).queryParam("includeActions", true)
                .get("/system/permissions/fd");
        assertEquals(Map.of("VOCAB_BILLING", Map.of("VOCAB_INVOICE", List.of("SEND"))), other.jsonPath().getMap(""));
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = "system")
    void fdWithoutApplicationIsUnchangedUnion() {
        Response all = given().queryParam("includeActions", true).get("/system/permissions/fd");
        assertEquals(200, all.statusCode(), all.asString());
        Map<String, Object> union = all.jsonPath().getMap("");
        assertTrue(union.containsKey("VOCAB_PLANNING") && union.containsKey("VOCAB_BILLING"),
                "both applications' stored domains stay in the union: " + union.keySet());
        assertTrue(union.keySet().stream().anyMatch("system"::equalsIgnoreCase),
                "code-discovered vocabulary stays in the union: " + union.keySet());
        assertFalse(union.containsKey("VOCAB_EMPTY"), "the union is built from domains, as before");
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = "system")
    void areasReadByApplication() {
        Response response = given().queryParam("applicationId", APP_A).get("/system/permissions/fd/areas");
        assertEquals(200, response.statusCode(), response.asString());
        assertEquals(List.of("VOCAB_EMPTY", "VOCAB_PLANNING"), response.jsonPath().getList("refName"));
        assertEquals(List.of(APP_A, APP_A), response.jsonPath().getList("applicationId"));

        assertEquals(400, given().get("/system/permissions/fd/areas").statusCode());
        Response unknown = given().queryParam("applicationId", "vocab-read-missing").get("/system/permissions/fd/areas");
        assertEquals(404, unknown.statusCode());
        assertEquals("APPLICATION_UNKNOWN", unknown.jsonPath().getString("code"));
        assertEquals(404, given().queryParam("applicationId", "vocab-read-missing").get("/system/permissions/fd").statusCode());
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = "system")
    void checkResolvesGroupRolesForTheAskedApplication() {
        List<String> forA = roles(check(APP_A));
        assertTrue(forA.contains("vocab-a-role"), forA.toString());
        assertFalse(forA.contains("vocab-b-role"), "another application's group must not count: " + forA);

        List<String> forB = roles(check(APP_B));
        assertTrue(forB.contains("vocab-b-role"), forB.toString());
        assertFalse(forB.contains("vocab-a-role"), forB.toString());
    }

    private Response check(String applicationId) {
        Map<String, Object> body = new HashMap<>();
        body.put("identity", USER);
        body.put("realm", testUtils.getTestRealm());
        body.put("applicationId", applicationId);
        body.put("area", "VOCAB_PLANNING");
        body.put("functionalDomain", "VOCAB_PLAN");
        body.put("action", "VIEW");
        Response response = given().contentType("application/json").body(body).post("/system/permissions/check");
        assertEquals(200, response.statusCode(), response.asString());
        return response;
    }

    private static List<String> roles(Response response) {
        return response.jsonPath().getList("roleAssignments.role");
    }

    private FunctionalArea area(String applicationId, String refName) {
        FunctionalArea area = new FunctionalArea();
        area.setRefName(refName);
        area.setDisplayName(refName);
        area.setApplicationId(applicationId);
        area.setDataDomain(securityUtils.getSystemDataDomain());
        return area;
    }

    private FunctionalDomain domain(String applicationId, String area, String refName, String... actions) {
        FunctionalDomain domain = new FunctionalDomain();
        domain.setRefName(refName);
        domain.setDisplayName(refName);
        domain.setApplicationId(applicationId);
        domain.setArea(area);
        List<FunctionalAction> list = new ArrayList<>();
        for (String action : actions) {
            FunctionalAction row = new FunctionalAction();
            row.setRefName(action);
            row.setDisplayName(action);
            list.add(row);
        }
        domain.setFunctionalActions(list);
        domain.setDataDomain(securityUtils.getSystemDataDomain());
        return domain;
    }

    private UserGroup group(String refName, String applicationId, String role, UserProfile member) {
        UserGroup group = new UserGroup();
        group.setRefName(refName);
        group.setDisplayName(refName);
        group.setApplicationId(applicationId);
        group.setRoles(List.of(role));
        group.setDataDomain(testUtils.getTestDataDomain());
        group.setUserProfiles(new ArrayList<>(List.of(member.createEntityReference())));
        return group;
    }
}
