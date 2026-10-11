package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.persistent.BaseRepoTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static dev.morphia.query.filters.Filters.eq;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CSV columns are bound with a Dozer bean reader, which follows nested paths. A column such as
 * {@code dataDomain.tenantId} must not let the uploader choose where imported rows live.
 */
@QuarkusTest
@io.quarkus.test.security.TestSecurity(user = "system@system.com", roles = {"admin"})
public class CsvImportDataDomainGovernanceIT extends BaseRepoTest {

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
    public void control_import_without_dataDomain_columns_uses_the_principal_domain() {
        String category = "csvdd-ctl-" + System.nanoTime();
        try {
            Response response = importCsv(
                    List.of("refName", "category", "key", "description"),
                    category + ":k," + category + ",k,control\n");

            assertEquals(200, response.statusCode(), response.asString());
            CodeList stored = findStored(category);
            assertNotNull(stored, response.asString());
            assertNotEquals("forged-tenant", stored.getDataDomain().getTenantId());
        } finally {
            deleteStored(category);
        }
    }

    @Test
    public void direct_import_with_nested_dataDomain_columns_is_rejected() {
        assertRejected(List.of("refName", "category", "key", "description",
                "dataDomain.orgRefName", "dataDomain.accountNum", "dataDomain.tenantId", "dataDomain.ownerId"),
                ",forged-org,forged-account,forged-tenant,forged-owner");
    }

    @Test
    public void direct_import_with_single_dataDomain_component_is_rejected() {
        assertRejected(List.of("refName", "category", "key", "description", "dataDomain.tenantId"),
                ",forged-tenant");
    }

    @Test
    public void session_import_with_nested_dataDomain_columns_is_rejected() {
        String category = "csvdd-sess-" + System.nanoTime();
        try {
            Response response = given().header("X-Realm", realm)
                    .multiPart("file", "import.csv",
                            ("refName,category,key,description,dataDomain.tenantId\n"
                                    + category + ":k," + category + ",k,attack,forged-tenant\n")
                                    .getBytes(StandardCharsets.UTF_8), "text/csv")
                    .queryParam("requestedColumns", "refName", "category", "key", "description", "dataDomain.tenantId")
                .when()
                    .post(PATH + "/csv/session")
                .then()
                    .extract().response();

            assertEquals(400, response.statusCode(), response.asString());
            assertTrue(response.asString().contains("dataDomain"), response.asString());
            assertNull(findStored(category), "no row may be staged or persisted");
        } finally {
            deleteStored(category);
        }
    }

    private void assertRejected(List<String> columns, String attackSuffix) {
        String category = "csvdd-atk-" + System.nanoTime();
        try {
            Response response = importCsv(columns,
                    category + ":k," + category + ",k,attack" + attackSuffix + "\n");

            assertEquals(400, response.statusCode(), response.asString());
            assertTrue(response.asString().contains("dataDomain"),
                    "the rejection should name the offending column: " + response.asString());
            assertNull(findStored(category), "a forged-domain row must not be persisted");
        } finally {
            deleteStored(category);
        }
    }

    private Response importCsv(List<String> columns, String rows) {
        String csv = String.join(",", columns) + "\n" + rows;
        return given().header("X-Realm", realm)
                .multiPart("file", "import.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv")
                .queryParam("requestedColumns", columns.toArray())
            .when()
                .post(PATH + "/csv")
            .then()
                .extract().response();
    }

    // Direct datastore access: forged-domain rows are invisible to the principal's governed reads.
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
