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

import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.security.runtime.RuleContext;
import java.util.HashMap;
import java.util.List;
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

    @Inject
    RuleContext ruleContext;

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
            DataDomain dd = testUtils.getTestDataDomain();
            cred.setDataDomain(dd);
            cred.setDomainContext(new DomainContext(dd, testUtils.getTestRealm()));
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

        resp.then()
            .statusCode(200)
            .body("rows", notNullValue())
            .body("rows.size()", greaterThan(0))
            .body("rows[0].email", equalTo("agg-user@test"))
            .body("rows[0].credentialUserIdPasswordRef", notNullValue())
            .body("rows[0].credentialUserIdPasswordRef.userId", equalTo("agg-user@test"));
    }

    @Test
    public void aggregation_enforces_root_row_security_across_tenants() {
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            // Seed a user profile in test tenant (allowed for current principal)
            DataDomain ddAllowed = testUtils.getTestDataDomain();
            CredentialUserIdPassword credAllowed = new CredentialUserIdPassword();
            credAllowed.setUserId("iso-allowed@test");
            credAllowed.setSubject("iso-allowed@test");
            credAllowed.setDataDomain(ddAllowed);
            credAllowed.setDomainContext(new DomainContext(ddAllowed, testUtils.getTestRealm()));
            credAllowed = ds.save(credAllowed);

            UserProfile upAllowed = new UserProfile();
            upAllowed.setEmail("iso-allowed@test");
            upAllowed.setRefName("iso-allowed@test");
            upAllowed.setDisplayName("Allowed Tenant User");
            upAllowed.setDataDomain(ddAllowed);
            EntityReference refAllowed = new EntityReference();
            refAllowed.setEntityId(credAllowed.getId());
            refAllowed.setEntityRefName(credAllowed.getSubject());
            refAllowed.setEntityDisplayName("CredentialRef");
            refAllowed.setRealm(ddAllowed.getTenantId());
            refAllowed.setEntityType(CredentialUserIdPassword.class.getName());
            upAllowed.setCredentialUserIdPasswordRef(refAllowed);
            ds.save(upAllowed);

            // Seed a user profile in a foreign tenant (forbidden for current principal)
            DataDomain ddForbidden = new DataDomain("otherOrg", "9999999999", "foreign-tenant-xyz", 0, "foreignOwner");
            CredentialUserIdPassword credForbidden = new CredentialUserIdPassword();
            credForbidden.setUserId("iso-forbidden@test");
            credForbidden.setSubject("iso-forbidden@test");
            credForbidden.setDataDomain(ddForbidden);
            credForbidden.setDomainContext(new DomainContext(ddForbidden, "foreign-tenant-xyz"));
            credForbidden = ds.save(credForbidden);

            UserProfile upForbidden = new UserProfile();
            upForbidden.setEmail("iso-forbidden@test");
            upForbidden.setRefName("iso-forbidden@test");
            upForbidden.setDisplayName("Forbidden Tenant User");
            upForbidden.setDataDomain(ddForbidden);
            EntityReference refForbidden = new EntityReference();
            refForbidden.setEntityId(credForbidden.getId());
            refForbidden.setEntityRefName(credForbidden.getSubject());
            refForbidden.setEntityDisplayName("CredentialRef");
            refForbidden.setRealm(ddForbidden.getTenantId());
            refForbidden.setEntityType(CredentialUserIdPassword.class.getName());
            upForbidden.setCredentialUserIdPasswordRef(refForbidden);
            ds.save(upForbidden);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("rootType", UserProfile.class.getName());
        body.put("query", "expand(credentialUserIdPasswordRef) && email:*iso-*");
        body.put("realm", realm);

        io.restassured.response.Response resp = given()
            .header("X-Realm", realm)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/query/find");

        resp.then()
            .statusCode(200)
            .body("rows", notNullValue())
            .body("rows.size()", greaterThan(0))
            .body("rows.email", hasItem("iso-allowed@test"))
            .body("rows.email", not(hasItem("iso-forbidden@test")));
    }

    @Test
    public void aggregation_enforces_field_exclusions() {
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        seedOneUserWithCredential(ds);

        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity("admin").withArea("*").withFunctionalDomain("*").withAction("*").build();
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withOrgRefName("*").withAccountNumber("*").withRealm("*")
                .withTenantId("*").withOwnerId("*").withDataSegment("*").build();
        Rule excludeRule = new Rule.Builder()
                .withName("gateway-agg-exclude-displayName-" + System.currentTimeMillis())
                .withSecurityURI(new SecurityURI(header, body))
                .withEffect(RuleEffect.ALLOW)
                .withExcludedFields(List.of("displayName"))
                .withPriority(-50)
                .withFinalRule(false)
                .build();
        ruleContext.addRule(header, excludeRule);
        ruleContext.clearCacheForRealm(realm);
        RuleContext.clearRequestCache();

        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("rootType", UserProfile.class.getName());
            reqBody.put("query", "expand(credentialUserIdPasswordRef) && email:*agg-user*");
            reqBody.put("realm", realm);

            io.restassured.response.Response resp = given()
                .header("X-Realm", realm)
                .contentType(ContentType.JSON)
                .body(reqBody)
            .when()
                .post("/api/query/find");

            resp.then()
                .statusCode(200)
                .body("rows", notNullValue())
                .body("rows.size()", greaterThan(0))
                .body("rows[0].email", equalTo("agg-user@test"))
                .body("rows[0].displayName", nullValue());
        } finally {
            ruleContext.clear();
            ruleContext.ensureDefaultRules();
        }
    }
}
