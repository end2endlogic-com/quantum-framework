package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.CodeList;
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
 * The {@code pairs} query parameter of the field-update endpoints must parse completely. A key the
 * grammar does not accept (such as a nested path) must be refused, not truncated to its matching
 * tail, which used to apply the update to a different field than the caller named.
 */
@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "system@system.com", roles = {"admin"})
public class PairParamParsingIT extends BaseRepoTest {

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
    public void well_formed_pairs_update_the_named_field() {
        String category = "pairs-ok-" + System.nanoTime();
        try {
            CodeList stored = create(category);

            Response response = set(stored, "[description:'updated, with comma']");

            assertEquals(200, response.statusCode(), response.asString());
            assertEquals("updated, with comma", findStored(category).getDescription());
        } finally {
            deleteStored(category);
        }
    }

    @Test
    public void nested_path_key_is_rejected_and_no_other_field_is_updated() {
        String category = "pairs-nested-" + System.nanoTime();
        try {
            CodeList stored = create(category);

            // The previous parser truncated this to description:pwned.
            Response response = set(stored, "category.description:pwned");

            assertEquals(400, response.statusCode(), response.asString());
            assertTrue(response.asString().contains("category.description:pwned"), response.asString());
            assertEquals("original", findStored(category).getDescription(),
                    "a malformed key must not be applied to another field");
        } finally {
            deleteStored(category);
        }
    }

    @Test
    public void malformed_entry_is_rejected_instead_of_skipped() {
        String category = "pairs-skip-" + System.nanoTime();
        try {
            CodeList stored = create(category);

            Response response = set(stored, "description:changed,not-a-pair");

            assertEquals(400, response.statusCode(), response.asString());
            assertEquals("original", findStored(category).getDescription());
        } finally {
            deleteStored(category);
        }
    }

    private CodeList create(String category) {
        Map<String, Object> entity = new HashMap<>();
        entity.put("category", category);
        entity.put("key", "k");
        entity.put("refName", category + ":k");
        entity.put("description", "original");
        entity.put("valueType", "STRING");
        Response created = given().header("X-Realm", realm)
                .contentType(ContentType.JSON)
                .body(entity)
            .when()
                .post(PATH)
            .then()
                .extract().response();
        assertEquals(200, created.statusCode(), created.asString());
        return findStored(category);
    }

    private Response set(CodeList stored, String pairs) {
        return given().header("X-Realm", realm)
                .contentType(ContentType.JSON)
                .queryParam("id", stored.getId().toHexString())
                .queryParam("pairs", pairs)
            .when()
                .put(PATH + "/set")
            .then()
                .extract().response();
    }

    private CodeList findStored(String category) {
        return morphiaDataStoreWrapper.getDataStore(realm)
                .find(CodeList.class)
                .filter(eq("category", category))
                .first();
    }

    private void deleteStored(String category) {
        morphiaDataStoreWrapper.getDataStore(realm)
                .find(CodeList.class)
                .filter(eq("category", category))
                .delete(new dev.morphia.DeleteOptions().multi(true));
    }
}
