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
 * End-to-end (HTTP, replica-set Mongo): the {@code admin} default rule scopes rows with
 * {@code dataDomain.tenantId:${pTenantId}}. A by-id write on another tenant's row must answer 404
 * and leave the row unchanged.
 */
@QuarkusTest
@TestSecurity(user = "test@system.com", roles = {"admin", "user"})
public class SecurityGatesFollowupRestTest extends BaseRepoTest {

    @Inject
    MorphiaDataStoreWrapper dataStoreWrapper;

    @Inject
    CredentialRepo credRepo;

    private String realm;
    private String marker;
    private DataDomain own;
    private CodeList ownRow;
    private CodeList foreignRow;

    @BeforeEach
    void setUp() {
        realm = testUtils.getTestRealm();
        marker = "sgfrest-" + System.nanoTime();
        own = testUtils.getTestDataDomain();
        DataDomain foreign = new DataDomain(own.getOrgRefName(), own.getAccountNum(), own.getTenantId() + "-sgf-other",
                own.getDataSegment(), "sgf-other-owner");
        ensureCredential();
        ownRow = seed(own, "own");
        foreignRow = seed(foreign, "foreign");
    }

    @AfterEach
    void tearDown() {
        dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("codeList")
                .deleteMany(new org.bson.Document("category", marker));
    }

    @Test
    void toggleActiveStatusOnForeignRowIs404AndWritesNothing() {
        given().header("X-Realm", realm)
                .queryParam("activeStatus", "INACTIVE")
                .when().put("/integration/codelists/activeStatus/" + foreignRow.getId().toHexString())
                .then().statusCode(404);
        org.bson.Document stored = dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("codeList")
                .find(new org.bson.Document("_id", foreignRow.getId())).first();
        assertTrue(stored != null && !"INACTIVE".equals(stored.getString("activeStatus")),
                "PUT activeStatus changed another tenant's row: " + stored);
    }

    @Test
    void toggleActiveStatusOnOwnRowStillWorks() {
        given().header("X-Realm", realm)
                .queryParam("activeStatus", "INACTIVE")
                .when().put("/integration/codelists/activeStatus/" + ownRow.getId().toHexString())
                .then().statusCode(200);
        org.bson.Document stored = dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("codeList")
                .find(new org.bson.Document("_id", ownRow.getId())).first();
        assertEquals("INACTIVE", stored.getString("activeStatus"));
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
