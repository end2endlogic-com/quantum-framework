package com.e2eq.framework.rest.contract;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Contract guard on the served OpenAPI document: one Java type gets one component schema.
 *
 * <p>SmallRye registers a schema per resolved type. When the same class is reached once as a
 * type-variable bound (for example {@code StaticDynamicList<T extends UnversionedBaseModel>.items})
 * and once as an annotation {@code implementation}, it registers two identical schemas and names
 * the second {@code UnversionedBaseModel1}. Generated SDKs then expose that suffixed type. This
 * test fails on any {@code NameN} component identical to an existing {@code Name} component
 * (distinct types that merely share a simple name, such as nested {@code Status} enums, are
 * left alone), and pins the entityref list schemas to their shared components.
 */
@QuarkusTest
class OpenApiSchemaNameContractTest {

    private static final Pattern SUFFIXED = Pattern.compile("^(.+?)(\\d+)$");

    private static JsonPath document() {
        return given().when().get("/q/openapi?format=json").then().statusCode(200).extract().jsonPath();
    }

    @Test
    void noComponentSchemaIsADeDuplicatedCopy() {
        Map<String, Object> schemas = document().getMap("components.schemas");
        List<String> copies = new ArrayList<>();
        for (String name : schemas.keySet()) {
            Matcher m = SUFFIXED.matcher(name);
            if (m.matches() && schemas.containsKey(m.group(1))
                    && schemas.get(m.group(1)).equals(schemas.get(name))) {
                copies.add(name + " (copy of " + m.group(1) + ")");
            }
        }
        assertTrue(copies.isEmpty(), "OpenAPI component schemas duplicated under a suffixed name: " + copies
                + ". Give both registrations the same resolved type (see StaticDynamicList.getItems).");
    }

    @Test
    void entityRefListsReferenceTheSharedSchemas() {
        JsonPath doc = document();
        Map<String, Map<String, Object>> paths = doc.getMap("paths");
        int checked = 0;
        for (String path : paths.keySet()) {
            if (!path.endsWith("/entityref")) {
                continue;
            }
            String base = "paths.'" + path + "'";
            if (doc.get(base + ".get") != null) {
                assertEquals("array", doc.getString(base + ".get.responses.'200'.content.'application/json'.schema.type"), path);
                assertEquals("#/components/schemas/EntityReference",
                        doc.getString(base + ".get.responses.'200'.content.'application/json'.schema.items.$ref"), "GET " + path);
                checked++;
            }
            if (doc.get(base + ".post") != null) {
                assertEquals("array", doc.getString(base + ".post.responses.'200'.content.'application/json'.schema.type"), path);
                assertEquals("#/components/schemas/UnversionedBaseModel",
                        doc.getString(base + ".post.responses.'200'.content.'application/json'.schema.items.$ref"), "POST " + path);
                checked++;
            }
        }
        assertFalse(checked == 0, "no entityref operations found in the served OpenAPI document");
    }
}
