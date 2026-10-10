package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.security.runtime.SecuritySession;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static dev.morphia.query.filters.Filters.eq;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A typed {@code BaseResource} write must never let the request body choose where a row lives.
 * Creates resolve the DataDomain from the principal, id-carrying saves keep the stored row's domain,
 * and a body naming any other domain is rejected with a typed error instead of being persisted.
 */
@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "system@system.com", roles = {"admin"})
public class BaseResourceDataDomainGovernanceIT extends BaseRepoTest {

    private static final String PATH = "/integration/codelists";

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    private String realm;

    @BeforeEach
    public void setUp() {
        realm = testUtils.getTestRealm();
        morphiaDataStoreWrapper.getDataStore(realm);
    }

    @Test
    public void create_with_forged_dataDomain_is_rejected_and_not_persisted() {
        String category = "ddgov-create-" + System.nanoTime();
        try {
            Map<String, Object> attack = codeList(category, "k");
            attack.put("dataDomain", forgedDomain());

            Response response = post(attack);

            assertEquals(403, response.statusCode(), response.asString());
            assertEquals("DATA_DOMAIN_NOT_PERMITTED", response.jsonPath().getString("statusMessage"));
            assertEquals(0, countStored(category), "a forged-domain create must not be persisted");
        } finally {
            deleteStored(category);
        }
    }

    @Test
    public void create_without_dataDomain_resolves_principal_domain_and_round_trip_is_accepted() {
        String category = "ddgov-ok-" + System.nanoTime();
        try {
            Response created = post(codeList(category, "a"));
            assertEquals(200, created.statusCode(), created.asString());
            DataDomain principalDomain = findStored(category, "a").getDataDomain();
            assertPrincipalDomain(principalDomain);

            // Echoing the caller's own domain back (e.g. a client that round-trips the entity) is fine.
            Map<String, Object> echo = codeList(category, "b");
            echo.put("dataDomain", asMap(principalDomain));
            Response echoed = post(echo);
            assertEquals(200, echoed.statusCode(), echoed.asString());
            assertEquals(principalDomain, findStored(category, "b").getDataDomain());
        } finally {
            deleteStored(category);
        }
    }

    @Test
    public void update_with_forged_dataDomain_is_rejected_and_stored_domain_is_unchanged() {
        String category = "ddgov-upd-" + System.nanoTime();
        try {
            Response created = post(codeList(category, "k"));
            assertEquals(200, created.statusCode(), created.asString());
            CodeList stored = findStored(category, "k");
            DataDomain original = stored.getDataDomain();

            Map<String, Object> attack = codeList(category, "k");
            attack.put("id", stored.getId().toHexString());
            attack.put("version", stored.getVersion());
            attack.put("description", "moved");
            attack.put("dataDomain", forgedDomain());

            Response response = post(attack);

            assertEquals(403, response.statusCode(), response.asString());
            assertEquals("DATA_DOMAIN_NOT_PERMITTED", response.jsonPath().getString("statusMessage"));
            CodeList after = findStored(category, "k");
            assertEquals(original, after.getDataDomain(), "the stored row must keep its domain");
            assertNotEquals("moved", after.getDescription());
        } finally {
            deleteStored(category);
        }
    }

    @Test
    public void update_without_dataDomain_keeps_the_stored_domain() {
        String category = "ddgov-upd-ok-" + System.nanoTime();
        try {
            Response created = post(codeList(category, "k"));
            assertEquals(200, created.statusCode(), created.asString());
            CodeList stored = findStored(category, "k");

            Map<String, Object> update = codeList(category, "k");
            update.put("id", stored.getId().toHexString());
            update.put("version", stored.getVersion());
            update.put("description", "updated");

            Response response = post(update);

            assertEquals(200, response.statusCode(), response.asString());
            CodeList after = findStored(category, "k");
            assertEquals("updated", after.getDescription());
            assertEquals(stored.getDataDomain(), after.getDataDomain());
        } finally {
            deleteStored(category);
        }
    }

    /**
     * A row that already lives in another domain is outside the caller's governed scope. Saving with
     * its id must not overwrite it (Morphia save is a replace-by-id) or pull it into the caller's domain.
     */
    @Test
    public void save_with_id_of_row_in_another_domain_is_not_found_and_row_is_untouched() {
        String category = "ddgov-foreign-" + System.nanoTime();
        try {
            ObjectId foreignId;
            // Programmatic placement into another domain is legitimate (seeders, provisioning).
            try (SecuritySession ignored = new SecuritySession(pContext, rContext)) {
                CodeList foreign = new CodeList();
                foreign.setCategory(category);
                foreign.setKey("k");
                foreign.setRefName(category + ":k");
                foreign.setDescription("foreign");
                foreign.setValueType("STRING");
                foreign.setDataDomain(DataDomain.builder()
                        .orgRefName("forged-org").accountNum("forged-account").tenantId("forged-tenant")
                        .ownerId("forged-owner").dataSegment(0).build());
                foreignId = morphiaDataStoreWrapper.getDataStore(realm).save(foreign).getId();
            }
            assertEquals("forged-tenant", findStored(category, "k").getDataDomain().getTenantId());

            Map<String, Object> attack = codeList(category, "k");
            attack.put("id", foreignId.toHexString());
            attack.put("version", findStored(category, "k").getVersion());
            attack.put("description", "hijacked");

            Response response = post(attack);

            assertEquals(404, response.statusCode(), response.asString());
            assertEquals("ENTITY_NOT_IN_SCOPE", response.jsonPath().getString("statusMessage"));
            CodeList after = findStored(category, "k");
            assertNotNull(after);
            assertEquals("foreign", after.getDescription());
            assertEquals("forged-tenant", after.getDataDomain().getTenantId());
        } finally {
            deleteStored(category);
        }
    }

    @Test
    public void field_path_update_of_dataDomain_is_rejected() {
        String category = "ddgov-set-" + System.nanoTime();
        try {
            Response created = post(codeList(category, "k"));
            assertEquals(200, created.statusCode(), created.asString());
            CodeList stored = findStored(category, "k");

            Response response = given().header("X-Realm", realm)
                    .contentType(ContentType.JSON)
                    .queryParam("id", stored.getId().toHexString())
                    .queryParam("pairs", "[dataDomain:'forged']")
                .when()
                    .put(PATH + "/set")
                .then()
                    .extract().response();

            assertEquals(403, response.statusCode(), response.asString());
            assertEquals("DATA_DOMAIN_NOT_UPDATABLE", response.jsonPath().getString("statusMessage"));
            assertEquals(stored.getDataDomain(), findStored(category, "k").getDataDomain());
        } finally {
            deleteStored(category);
        }
    }

    private void assertPrincipalDomain(DataDomain dd) {
        assertNotNull(dd);
        assertNotNull(dd.getTenantId());
        assertNotEquals("forged-tenant", dd.getTenantId());
        assertNotEquals("forged-org", dd.getOrgRefName());
    }

    private static Map<String, Object> forgedDomain() {
        Map<String, Object> forged = new HashMap<>();
        forged.put("orgRefName", "forged-org");
        forged.put("accountNum", "forged-account");
        forged.put("tenantId", "forged-tenant");
        forged.put("ownerId", "forged-owner");
        forged.put("dataSegment", 0);
        return forged;
    }

    private static Map<String, Object> asMap(DataDomain dd) {
        Map<String, Object> m = new HashMap<>();
        m.put("orgRefName", dd.getOrgRefName());
        m.put("accountNum", dd.getAccountNum());
        m.put("tenantId", dd.getTenantId());
        m.put("ownerId", dd.getOwnerId());
        m.put("dataSegment", dd.getDataSegment());
        if (dd.getBusinessTransactionId() != null) m.put("businessTransactionId", dd.getBusinessTransactionId());
        if (dd.getLocationId() != null) m.put("locationId", dd.getLocationId());
        return m;
    }

    private static Map<String, Object> codeList(String category, String key) {
        Map<String, Object> entity = new HashMap<>();
        entity.put("category", category);
        entity.put("key", key);
        entity.put("refName", category + ":" + key);
        entity.put("description", "BaseResource dataDomain governance regression");
        entity.put("valueType", "STRING");
        return entity;
    }

    private Response post(Map<String, Object> entity) {
        return given().header("X-Realm", realm)
                .contentType(ContentType.JSON)
                .body(entity)
            .when()
                .post(PATH)
            .then()
                .extract().response();
    }

    // Direct datastore access: forged-domain rows are invisible to the principal's governed reads.
    private CodeList findStored(String category, String key) {
        return morphiaDataStoreWrapper.getDataStore(realm)
                .find(CodeList.class)
                .filter(eq("category", category), eq("key", key))
                .first();
    }

    private long countStored(String category) {
        return morphiaDataStoreWrapper.getDataStore(realm)
                .find(CodeList.class)
                .filter(eq("category", category))
                .count();
    }

    private void deleteStored(String category) {
        morphiaDataStoreWrapper.getDataStore(realm)
                .find(CodeList.class)
                .filter(eq("category", category))
                .delete(new dev.morphia.DeleteOptions().multi(true));
    }
}
