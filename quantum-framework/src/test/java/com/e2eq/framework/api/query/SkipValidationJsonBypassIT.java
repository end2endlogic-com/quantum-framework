package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.persistent.BaseRepoTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static dev.morphia.query.filters.Filters.eq;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code skipValidation} is an internal, programmatic switch that bypasses data-domain resolution,
 * bean validation and state-graph checks. A REST caller must not be able to turn it on by putting
 * {@code "skipValidation": true} in a save body.
 */
@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "system@system.com", roles = {"admin"})
public class SkipValidationJsonBypassIT extends BaseRepoTest {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    private String realm;

    @BeforeEach
    public void setUp() {
        // BaseRepoTest migrates the test realm before the first test runs.
        realm = testUtils.getTestRealm();
        morphiaDataStoreWrapper.getDataStore(realm);
    }

    /**
     * The typed {@code BaseResource} save has no resource-level DataDomain stamping; it relies on
     * {@code ValidationInterceptor} to resolve the domain from the principal. A body-supplied
     * {@code skipValidation=true} used to skip that resolution and persist a record with no domain.
     */
    @Test
    public void baseResource_save_with_skipValidation_true_still_resolves_dataDomain_from_principal() {
        String suffix = Long.toString(System.nanoTime());
        String controlCategory = "skipval-br-ctl-" + suffix;
        String category = "skipval-br-atk-" + suffix;
        String key = "key-" + suffix;
        try {
            Response controlResponse = saveViaBaseResource(codeList(controlCategory, key));
            assertEquals(200, controlResponse.statusCode(), controlResponse.asString());
            CodeList control = findStored(controlCategory, key);
            assertNotNull(control, "control entity should have been persisted");
            DataDomain expected = control.getDataDomain();
            assertNotNull(expected, "control save must have a resolved dataDomain");

            Map<String, Object> attack = codeList(category, key);
            attack.put("skipValidation", true);

            Response attackResponse = saveViaBaseResource(attack);
            assertEquals(200, attackResponse.statusCode(), attackResponse.asString());
            assertFalse(attackResponse.asString().contains("\"skipValidation\":true"),
                    "skipValidation must not be bound from the request body: " + attackResponse.asString());

            CodeList stored = findStored(category, key);
            assertNotNull(stored, "entity should have been persisted");
            assertNotNull(stored.getDataDomain(), "dataDomain must be resolved even when the body asks to skip validation");
            assertEquals(expected.getOrgRefName(), stored.getDataDomain().getOrgRefName());
            assertEquals(expected.getAccountNum(), stored.getDataDomain().getAccountNum());
            assertEquals(expected.getTenantId(), stored.getDataDomain().getTenantId());
            assertEquals(expected.getOwnerId(), stored.getDataDomain().getOwnerId());
        } finally {
            deleteStored(controlCategory);
            deleteStored(category);
        }
    }

    /** QueryGateway stamps the domain itself; keep it covered so the guarantee holds on both paths. */
    @Test
    public void queryGateway_save_with_skipValidation_true_still_resolves_dataDomain_from_principal() {
        assertSkipValidationCannotForgeDataDomain("skipval-qg-", this::save);
    }

    private void assertSkipValidationCannotForgeDataDomain(String prefix,
                                                          java.util.function.Function<Map<String, Object>, Response> saver) {
        String suffix = Long.toString(System.nanoTime());

        // Control: a normal save shows which data domain the principal resolves to.
        String controlCategory = prefix + "ctl-" + suffix;
        Response controlResponse = saver.apply(codeList(controlCategory, "key-" + suffix));
        String category = prefix + "atk-" + suffix;
        String key = "key-" + suffix;
        try {
            assertEquals(200, controlResponse.statusCode(), controlResponse.asString());
            CodeList control = findStored(controlCategory, "key-" + suffix);
            assertNotNull(control, "control entity should have been persisted");
            DataDomain expected = control.getDataDomain();
            assertNotNull(expected, "control save must have a resolved dataDomain");

            // Attack: skipValidation=true plus a forged data domain.
            Map<String, Object> forgedDomain = new HashMap<>();
            forgedDomain.put("orgRefName", "forged-org");
            forgedDomain.put("accountNum", "forged-account");
            forgedDomain.put("tenantId", "forged-tenant");
            forgedDomain.put("ownerId", "forged-owner");
            forgedDomain.put("dataSegment", 0);

            Map<String, Object> attack = codeList(category, key);
            attack.put("skipValidation", true);
            attack.put("dataDomain", forgedDomain);

            Response attackResponse = saver.apply(attack);
            assertEquals(200, attackResponse.statusCode(), attackResponse.asString());

            CodeList stored = findStored(category, key);
            assertNotNull(stored, "attack entity should have been persisted under the resolved domain");
            DataDomain actual = stored.getDataDomain();
            assertEquals(expected.getOrgRefName(), actual.getOrgRefName(), "orgRefName must come from the principal");
            assertEquals(expected.getAccountNum(), actual.getAccountNum(), "accountNum must come from the principal");
            assertEquals(expected.getTenantId(), actual.getTenantId(), "tenantId must come from the principal");
            assertEquals(expected.getOwnerId(), actual.getOwnerId(), "ownerId must come from the principal");
            assertFalse(attackResponse.asString().contains("\"skipValidation\":true"),
                    "skipValidation must not be bound from the request body: " + attackResponse.asString());
        } finally {
            // Remove directly: a forged-domain record is invisible to the principal's governed delete.
            deleteStored(controlCategory);
            deleteStored(category);
        }
    }

    private void deleteStored(String category) {
        morphiaDataStoreWrapper.getDataStore(realm)
                .find(CodeList.class)
                .filter(eq("category", category))
                .delete(new dev.morphia.DeleteOptions().multi(true));
    }

    private CodeList findStored(String category, String key) {
        return morphiaDataStoreWrapper.getDataStore(realm)
                .find(CodeList.class)
                .filter(eq("category", category), eq("key", key))
                .first();
    }

    private Response saveViaBaseResource(Map<String, Object> entity) {
        return given().header("X-Realm", realm)
                .contentType(ContentType.JSON)
                .body(entity)
            .when()
                .post("/integration/codelists")
            .then()
                .extract().response();
    }

    @Test
    public void save_with_skipValidation_true_still_runs_bean_validation() {
        String suffix = Long.toString(System.nanoTime());
        String category = "skipval-inv-" + suffix;
        String key = "skipval-inv-key-" + suffix;

        Map<String, Object> invalid = codeList(category, key);
        invalid.put("refName", "ab"); // violates @Size(min=3) on refName
        invalid.put("skipValidation", true);

        Response response = save(invalid);
        try {
            assertNotEquals(200, response.statusCode(),
                    "an invalid entity must be rejected even when the body asks to skip validation: " + response.asString());
            assertTrue(response.asString().contains("ref name must have a min size of 3 characters"),
                    "rejection should carry the bean-validation diagnostic: " + response.asString());

            long persisted = morphiaDataStoreWrapper.getDataStore(realm)
                    .find(CodeList.class)
                    .filter(eq("category", category), eq("key", key))
                    .count();
            assertEquals(0, persisted, "invalid entity must not be persisted");
        } finally {
            delete(response.jsonPath().getString("id"));
        }
    }

    private Map<String, Object> codeList(String category, String key) {
        Map<String, Object> entity = new HashMap<>();
        entity.put("category", category);
        entity.put("key", key);
        entity.put("refName", category + ":" + key);
        entity.put("description", "skipValidation JSON bypass regression");
        entity.put("valueType", "STRING");
        return entity;
    }

    private Response save(Map<String, Object> entity) {
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        body.put("entity", entity);
        return given().header("X-Realm", realm)
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/api/query/save")
            .then()
                .extract().response();
    }

    private void delete(String id) {
        if (id == null) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", "CodeList");
        body.put("realm", realm);
        body.put("id", id);
        given().header("X-Realm", realm)
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/api/query/delete");
    }
}
