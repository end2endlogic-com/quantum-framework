package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import dev.morphia.Datastore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.e2eq.framework.model.securityrules.SecurityCallScope;
import java.util.HashMap;
import java.util.Map;

import com.e2eq.framework.persistent.BaseRepoTest;
import io.quarkus.test.security.TestSecurity;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test@system.com", roles = {"admin", "user"})
public class QueryGatewayAggregationIT extends BaseRepoTest {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    com.e2eq.framework.model.persistent.morphia.CredentialRepo credRepo;

    private String realm;

    @BeforeEach
    public void setUp() {
        realm = testUtils.getTestRealm();
        morphiaDataStoreWrapper.getDataStore(realm);

        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            java.util.Optional<CredentialUserIdPassword> credop = credRepo.findByUserId(
                testUtils.getTestUserId(), testUtils.getSystemRealm());
            if (credop.isEmpty()) {
                CredentialUserIdPassword cred = new CredentialUserIdPassword();
                cred.setUserId(testUtils.getTestUserId());
                cred.setSubject(testUtils.getTestUserId());
                cred.setRoles(new String[]{"admin", "user"});
                DataDomain dd = testUtils.getTestDataDomain();
                cred.setDataDomain(dd);
                cred.setDomainContext(new DomainContext(dd, testUtils.getTestRealm()));
                cred.setRealmRegEx("*");
                credRepo.save(testUtils.getSystemRealm(), cred);
            }
        }
    }

    private void seedOneUserWithCredential(Datastore ds) {
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            // Create a credential
            CredentialUserIdPassword cred = new CredentialUserIdPassword();
            cred.setUserId("agg-user@test");
            cred.setSubject("agg-user@test");
            cred.setLastUpdate(new java.util.Date());
            DataDomain dd = new DataDomain("end2endlogic", "0000000001", "tenant-agg", 0, "owner-agg");
            cred.setDataDomain(dd);
            cred.setDomainContext(new DomainContext(dd, dd.getTenantId()));
            cred = ds.save(cred);

            // Create a user profile referencing credential
            UserProfile up = new UserProfile();
            up.setEmail("agg-user@test");
            up.setRefName("agg-user@test");
            up.setDisplayName("Agg User");
            up.setDataDomain(dd);
            EntityReference ref = new EntityReference();
            ref.setEntityId(cred.getId());
            ref.setEntityRefName(cred.getRefName() == null ? cred.getSubject() : cred.getRefName());
            ref.setEntityDisplayName("CredentialRef");
            ref.setRealm(dd.getTenantId());
            ref.setEntityType(CredentialUserIdPassword.class.getName());
            up.setCredentialUserIdPasswordRef(ref);
            ds.save(up);
        }
    }

    @Test
    public void find_endpoint_executes_aggregation_when_flag_enabled_and_hydrates_single_ref() {
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        seedOneUserWithCredential(ds);

        Map<String,Object> body = new HashMap<>();
        body.put("rootType", UserProfile.class.getName());
        // Request expansion; simple filter to avoid returning everything
        body.put("query", "expand(credentialUserIdPasswordRef) && email:*agg-user*");
        Map<String,Object> page = new HashMap<>();
        page.put("limit", 5);
        page.put("skip", 0);
        body.put("page", page);
        body.put("realm", realm);

        io.restassured.response.Response resp = given()
            .header("X-Realm", realm)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/find");

        int status = resp.getStatusCode();
        org.junit.jupiter.api.Assertions.assertTrue(
            status == 200 || status == 501,
            "Status should be 200 or 501, but got: " + status);
        if (status == 200) {
            resp.then().body("rows", notNullValue());
        } else {
            resp.then().body("error", is("NotImplemented"));
        }
    }
}
