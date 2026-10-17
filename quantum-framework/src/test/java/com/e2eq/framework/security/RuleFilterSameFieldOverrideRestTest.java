package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.persistent.BaseRepoTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (HTTP, replica-set Mongo): an authenticated tenant-A user who puts another tenant's
 * id in a filter query parameter or a QueryGateway query must not get that tenant's rows.
 *
 * <p>The {@code admin} default rule scopes reads with {@code dataDomain.tenantId:${pTenantId}}. The
 * caller's filter on the same field must be ANDed with it, never merged over it.</p>
 */
@QuarkusTest
@TestSecurity(user = "test@system.com", roles = {"admin", "user"})
public class RuleFilterSameFieldOverrideRestTest extends BaseRepoTest {

    @Inject
    MorphiaDataStoreWrapper dataStoreWrapper;

    @Inject
    CredentialRepo credRepo;

    private String realm;
    private String marker;
    private DataDomain own;
    private DataDomain foreign;
    private CodeList foreignRow;

    @BeforeEach
    void setUp() {
        realm = testUtils.getTestRealm();
        marker = "rfsorest-" + System.nanoTime();
        own = testUtils.getTestDataDomain();
        foreign = new DataDomain(own.getOrgRefName(), own.getAccountNum(), own.getTenantId() + "-rfso-other",
                own.getDataSegment(), "rfso-other-owner");
        ensureCredential();
        seed(own, "own");
        foreignRow = seed(foreign, "foreign");
    }

    @AfterEach
    void tearDown() {
        dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("codeList")
                .deleteMany(new org.bson.Document("category", marker));
    }

    @Test
    void restListFilterOnForeignTenantReturnsNothing() {
        List<Map<String, Object>> rows = given().header("X-Realm", realm)
                .queryParam("filter", "dataDomain.tenantId:" + foreign.getTenantId() + "&&category:" + marker)
                .when().get("/integration/codelists/list")
                .then().statusCode(200)
                .extract().jsonPath().getList("rows");
        assertTrue(rows == null || rows.isEmpty(), "REST list returned another tenant's rows: " + rows);
    }

    @Test
    void restGetByIdOfForeignRowIs404() {
        given().header("X-Realm", realm)
                .when().get("/integration/codelists/id/" + foreignRow.getId().toHexString())
                .then().statusCode(404);
    }

    @Test
    void queryGatewayFindAndCountOnForeignTenantReturnNothing() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", CodeList.class.getName());
        body.put("realm", realm);
        body.put("query", "dataDomain.tenantId:" + foreign.getTenantId());
        Map<String, Object> page = new HashMap<>();
        page.put("limit", 100);
        page.put("skip", 0);
        body.put("page", page);

        List<Map<String, Object>> rows = given().header("X-Realm", realm)
                .contentType(ContentType.JSON).body(body)
                .when().post("/api/query/find")
                .then().statusCode(200)
                .extract().jsonPath().getList("rows");
        assertFalse(rows != null && rows.stream().anyMatch(r -> marker.equals(r.get("category"))),
                "QueryGateway find returned another tenant's rows: " + rows);

        Map<String, Object> countBody = new HashMap<>(body);
        countBody.remove("page");
        Number count = given().header("X-Realm", realm)
                .contentType(ContentType.JSON).body(countBody)
                .when().post("/api/query/count")
                .then().statusCode(200)
                .extract().path("count");
        assertEquals(0L, count.longValue(), "QueryGateway count counted another tenant's rows");
    }

    @Test
    void queryGatewayDeleteManyOnForeignTenantDeletesNothing() {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", CodeList.class.getName());
        body.put("realm", realm);
        // The foreign tenant is synthetic, so a leak can only delete this test's own seed row.
        body.put("query", "dataDomain.tenantId:" + foreign.getTenantId());
        given().header("X-Realm", realm)
                .contentType(ContentType.JSON).body(body)
                .when().post("/api/query/deleteMany")
                .then().statusCode(200);
        long remaining = dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("codeList")
                .countDocuments(new org.bson.Document("_id", foreignRow.getId()));
        assertEquals(1L, remaining, "QueryGateway deleteMany deleted another tenant's row");
    }

    private CodeList seed(DataDomain dd, String key) {
        CodeList cl = new CodeList();
        cl.setCategory(marker);
        cl.setKey(key);
        cl.setRefName(marker + ":" + key);
        cl.setDescription("secret-" + key);
        cl.setValueType("STRING");
        cl.setDataDomain(dd);
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            return dataStoreWrapper.getDataStore(realm).save(cl);
        }
    }

    private void ensureCredential() {
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            Optional<CredentialUserIdPassword> existing =
                    credRepo.findByUserId(testUtils.getTestUserId(), testUtils.getSystemRealm());
            if (existing.isEmpty()) {
                CredentialUserIdPassword cred = new CredentialUserIdPassword();
                cred.setUserId(testUtils.getTestUserId());
                cred.setRefName(testUtils.getTestUserId());
                cred.setSubject(testUtils.getTestUserId());
                cred.setRoles(new String[]{"admin", "user"});
                cred.setDataDomain(own);
                cred.setDomainContext(new DomainContext(own, realm));
                cred.setRealmRegEx("*");
                credRepo.save(testUtils.getSystemRealm(), cred);
            }
        }
    }
}
