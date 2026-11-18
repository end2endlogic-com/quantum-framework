package com.e2eq.framework.rest.filters;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.security.principal.RealmRefNames;
import com.mongodb.client.MongoClient;
import dev.morphia.DeleteOptions;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 5/6 review: a realm named by caller input (a path parameter, a query parameter, an
 * unauthenticated X-Realm) opened, and so created, a database before any check. The request
 * boundary admits it first; startup and provisioning paths are unchanged.
 */
@QuarkusTest
class RequestRealmBoundaryHttpTest extends BaseRepoTest {

    @Inject MongoClient mongoClient;
    @Inject SystemDirectory systemDirectory;
    @Inject RealmRepo realmRepo;
    @Inject MorphiaDataStoreWrapper stores;
    @Inject com.e2eq.framework.util.EnvConfigUtils envConfigUtils;

    private Set<String> databases() {
        Set<String> names = new HashSet<>();
        mongoClient.listDatabaseNames().forEach(names::add);
        return names;
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin", "system"})
    void anUnregisteredOrReservedPathRealmIsRefusedTypedAndCreatesNoDatabase() {
        Set<String> before = databases();
        String run = UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 5; i++) {
            Response r = given().post("/user/userProfile/indexes/ensureIndexes/zz-path-" + run + "-" + i);
            assertEquals(403, r.statusCode(), r.asString());
            assertEquals("REALM_NOT_AUTHORIZED", r.jsonPath().getString("code"));
        }
        for (String reserved : new String[] {"admin", "local", "config"}) {
            Response r = given().post("/user/userProfile/indexes/ensureIndexes/" + reserved);
            assertEquals(403, r.statusCode(), reserved + ": " + r.asString());
        }
        Set<String> created = databases();
        created.removeAll(before);
        assertTrue(created.stream().noneMatch(name -> name.startsWith("zz-path-")), "databases created: " + created);
    }

    @Test
    @TestSecurity(user = "system@system.com", roles = {"admin", "system"})
    void theConfiguredDefaultRealmIsAdmittedOnThePath() {
        Response r = given().queryParam("collectionName", "userProfile")
                .post("/user/userProfile/indexes/ensureIndexes/" + envConfigUtils.getDefaultRealm());
        assertEquals(200, r.statusCode(), r.asString());
    }

    @Test
    void anUnauthenticatedUnknownXRealmCreatesNoDatabase() {
        Set<String> before = databases();
        String realm = "zz-anon-" + UUID.randomUUID().toString().substring(0, 6);
        Response r = given().header("X-Realm", realm).contentType("application/json")
                .body(Map.of("userId", "nobody@example.test", "password", "x")).post("/security/login");
        assertTrue(r.statusCode() < 500, r.asString());
        Set<String> created = databases();
        created.removeAll(before);
        assertTrue(created.stream().noneMatch(realm::equals), "databases created: " + created);
    }

    @Test
    void savingARealmRowDropsACachedUnknownAnswerAtOnce() {
        String refName = "zz-reg-" + UUID.randomUUID().toString().substring(0, 6);
        assertTrue(RealmRefNames.registered(systemDirectory, refName).isEmpty(), "not registered yet");
        Realm realm = new Realm();
        realm.setRefName(refName);
        realm.setDisplayName(refName);
        realm.setDatabaseName(refName);
        realm.setEmailDomain(refName + ".example.test");
        realm.setDomainContext(new com.e2eq.framework.model.security.DomainContext(
                testUtils.getTestDataDomain(), refName));
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            realmRepo.save(envConfigUtils.getSystemRealm(), realm);
        }
        try {
            assertEquals(refName, RealmRefNames.registered(systemDirectory, refName).orElse(null),
                    "a saved realm row resolves without waiting for the cached miss to expire");
        } finally {
            stores.getDataStore(envConfigUtils.getSystemRealm()).find(Realm.class)
                    .filter(Filters.eq("refName", refName)).delete(new DeleteOptions().multi(true));
        }
    }
}
