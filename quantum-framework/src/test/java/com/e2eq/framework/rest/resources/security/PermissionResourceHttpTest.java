package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.system.membership.RealmMembershipService;
import dev.morphia.DeleteOptions;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP routing, established request context and the real invite administrative authority. */
@QuarkusTest
class PermissionResourceHttpTest extends BaseRepoTest {
    private static final String ALICE = "permission-alice@test.example";
    private static final String BOB = "permission-bob@test.example";
    private static final String ADMIN = "permission-admin@test.example";
    private static final List<String> PATHS = List.of("role-provenance", "check", "check-with-index", "fd/evaluate");

    @Inject CredentialRepo credentials;
    @Inject MorphiaDataStoreWrapper stores;
    @Inject RealmMembershipService memberships;
    @Inject ApplicationRepo applications;
    private String realm;

    @BeforeEach
    void prepare() {
        realm = testUtils.getTestRealm();
        try (var ignored = asSystem()) {
            // check-with-index answers only for a registered application.
            applications.ensureRegistered("quantum-framework-test", testUtils.getSystemDataDomain());
            seed(ALICE, "user");
            seed(BOB, "user");
            seed(ADMIN, "user", "admin");
        }
    }

    private void seed(String user, String... roles) {
        var credential = new CredentialUserIdPassword();
        credential.setUserId(user);
        credential.setSubject(user);
        credential.setRefName(user);
        credential.setRoles(roles);
        credential.setDataDomain(testUtils.getTestDataDomain());
        credential.setDomainContext(new DomainContext(testUtils.getTestDataDomain(), realm));
        credentials.save(testUtils.getSystemRealm(), credential);
        memberships.upsertUserRealmRole(UserRealmRole.builder()
                .refName("permission-test:" + user).userId(user).realmRefName(realm)
                .roles(List.of(roles)).status(UserRealmRole.STATUS_ACTIVE)
                .authorizedTenantIds(List.of(testUtils.getTestDataDomain().getTenantId()))
                .dataDomain(testUtils.getTestDataDomain()).build());
    }

    @AfterEach
    void cleanup() {
        var system = stores.getDataStore(testUtils.getSystemRealm());
        system.find(CredentialUserIdPassword.class).filter(Filters.in("userId", List.of(ALICE, BOB, ADMIN)))
                .delete(new DeleteOptions().multi(true));
        system.find(UserRealmRole.class).filter(Filters.in("userId", List.of(ALICE, BOB, ADMIN)))
                .delete(new DeleteOptions().multi(true));
    }

    @Test
    @TestSecurity(user = ALICE, roles = "user")
    void selfRetainsResponseAndOmittedRealmUsesRequestContext() {
        for (String path : PATHS) {
            Response explicit = post(path, ALICE, realm, null);
            Response implicit = post(path, ALICE, null, null);
            if (path.equals("fd/evaluate")) {
                // Unchanged: evaluating the whole catalog reads governed rows, which the rules refuse to an
                // ordinary user. The inspection guard lets a self query through; it is not what refuses.
                ruleDenied(explicit);
                ruleDenied(implicit);
                continue;
            }
            assertEquals(200, explicit.statusCode(), explicit.asString());
            assertEquals(200, implicit.statusCode(), implicit.asString());
            if (path.equals("role-provenance")) {
                assertEquals(explicit.jsonPath().getMap(""), implicit.jsonPath().getMap(""));
                assertTrue(implicit.jsonPath().getBoolean("credentialFound"));
                assertEquals(List.of("user"), implicit.jsonPath().getList("credentialRoles"));
                assertTrue(implicit.jsonPath().getList("netRoles").contains("user"));
                assertEquals(realm, implicit.jsonPath().getString("realm"));
            }
            if (path.equals("check")) {
                assertEquals(explicit.jsonPath().getString("finalEffect"), implicit.jsonPath().getString("finalEffect"));
                assertNotNull(explicit.jsonPath().getString("finalEffect"));
            }
        }
    }

    @Test
    @TestSecurity(user = ALICE, roles = "user")
    void anotherUserMissingUserAndOtherRealmHaveIdenticalForbiddenResponses() {
        for (String path : PATHS) {
            Map<String, Object> existing = denied(post(path, BOB, realm, null));
            assertEquals(existing, denied(post(path, "permission-missing@test.example", realm, null)));
            assertEquals(existing, denied(post(path, ALICE, "not-the-request-realm", null)));
        }
    }

    @Test
    @TestSecurity(user = ALICE, roles = "user")
    void selfCannotSupplyUnheldRoles() {
        for (String path : List.of("check", "check-with-index", "fd/evaluate")) {
            denied(post(path, ALICE, realm, new String[]{"admin"}));
            Response held = post(path, ALICE, realm, new String[]{"user"});
            if (path.equals("fd/evaluate")) {
                ruleDenied(held);
                continue;
            }
            assertEquals(200, held.statusCode(), held.asString());
        }
    }

    @Test
    @TestSecurity(user = ADMIN, roles = {"user", "admin"})
    void realmAdministratorMayInspectAnotherUserAndSimulateRolesInOwnRealm() {
        for (String path : PATHS) {
            Response allowed = post(path, BOB, realm, path.equals("role-provenance") ? null : new String[]{"simulated-role"});
            assertEquals(200, allowed.statusCode(), allowed.asString());
            denied(post(path, BOB, "not-the-admin-realm", null));
        }
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = "system")
    void configuredSystemPrincipalMayInspectAnotherRealm() {
        for (String path : PATHS) {
            Response response = post(path, BOB, realm, null);
            assertEquals(200, response.statusCode(), response.asString());
            if (path.equals("role-provenance")) {
                assertTrue(response.jsonPath().getBoolean("credentialFound"));
                assertEquals(List.of("user"), response.jsonPath().getList("credentialRoles"));
            }
        }
    }

    private Response post(String path, String identity, String requestedRealm, String[] roles) {
        Map<String, Object> body = new HashMap<>();
        body.put(path.equals("role-provenance") ? "userId" : "identity", identity);
        if (requestedRealm != null) body.put("realm", requestedRealm);
        if (!path.equals("role-provenance")) {
            body.put("applicationId", "quantum-framework-test");
            body.put("area", "security");
            body.put("functionalDomain", "userProfile");
            body.put("action", "view");
            if (roles != null) body.put("roles", roles);
        }
        return given().contentType("application/json").body(body).post("/system/permissions/" + path);
    }

    /** Refused by the rule engine, as before this guard existed; not by the inspection guard. */
    private void ruleDenied(Response response) {
        assertEquals(403, response.statusCode(), response.asString());
        assertNotEquals("PERMISSION_INSPECTION_FORBIDDEN", response.jsonPath().getString("errorCode"));
    }

    private Map<String, Object> denied(Response response) {
        assertEquals(403, response.statusCode(), response.asString());
        assertEquals("PERMISSION_INSPECTION_FORBIDDEN", response.jsonPath().getString("errorCode"));
        return response.jsonPath().getMap("");
    }
}
