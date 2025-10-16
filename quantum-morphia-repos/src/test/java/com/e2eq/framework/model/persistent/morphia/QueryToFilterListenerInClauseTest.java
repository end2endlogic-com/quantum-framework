package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.mongodb.MongoClientSettings;
import dev.morphia.query.filters.Filter;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class QueryToFilterListenerInClauseTest {

    // Minimal dummy model just to satisfy modelClass parameter
    public static class DummyModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() {
            return "test-area";
        }

        @Override
        public String bmFunctionalDomain() {
            return "test-domain";
        }
    }

    private PrincipalContext principal() {
        return new PrincipalContext.Builder()
                .withUserId("user-1")
                .withDefaultRealm("test-realm")
                .withDataDomain(new com.e2eq.framework.model.persistent.base.DataDomain(
                        "end2endlogic", "0000000001", "tenant-1", 0, "owner-1"))
                .build();
    }

    private ResourceContext resource() {
        return new ResourceContext.Builder()
                .withArea("sales")
                .withFunctionalDomain("order")
                .withAction("view")
                .withResourceId("res-1")
                .build();
    }

    @Test
    public void testInWithLiteralOids() {
        String q = "customerId:^[5f1e1a5e5e5e5e5e5e5e5e51,5f1e1a5e5e5e5e5e5e5e5e52]";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertEquals("$in", f.getName());
        assertEquals("customerId", f.getField());
        Object v = f.getValue();
        assertTrue(v instanceof List, "Value should be a List");
        List<?> list = (List<?>) v;
        assertEquals(2, list.size());
        assertTrue(list.get(0) instanceof ObjectId);
        assertEquals("5f1e1a5e5e5e5e5e5e5e5e51", ((ObjectId) list.get(0)).toHexString());
    }

    @Test
    public void testInWithVariableCommaSeparatedString() {
        String q = "customerId:^[${ownerId}]";
        Map<String, Object> extra = new HashMap<>();
        // Using string list. The listener should split and coerce to ObjectIds
        Map<String, String> std = MorphiaUtils.createStandardVariableMapFrom(principal(), resource());
        MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(principal(), resource(), extra);
        // Insert into the strings map using an allowed VARIABLE name (ownerId) for CSV expansion
        vars.strings.put("ownerId", "5f1e1a5e5e5e5e5e5e5e5e51,5f1e1a5e5e5e5e5e5e5e5e52");

        Filter f = MorphiaUtils.convertToFilter(q, vars, DummyModel.class);
        assertEquals("$in", f.getName());
        assertEquals("customerId", f.getField());
        // As a resilience check, just assert the stringified filter contains both OIDs
        String s = f.toString();
        assertTrue(s.contains("5f1e1a5e5e5e5e5e5e5e5e51"));
        assertTrue(s.contains("5f1e1a5e5e5e5e5e5e5e5e52"));
    }

    @Test
    public void inList_preserves_numeric_strings() {
        String q = "refName:^[\"LOCATION-648\",\"09876543\",\"0606\",\"walmart300\"]";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$in", f.getName());
        assertEquals("refName", f.getField());

        Object v = f.getValue();
        assertTrue(v instanceof List, "Value should be a List");
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) v;

        assertEquals(4, list.size());
        // exact values
        assertEquals("LOCATION-648", list.get(0));
        assertEquals("09876543", list.get(1)); // leading zeros preserved
        assertEquals("0606", list.get(2)); // not coerced to 606
        assertEquals("walmart300", list.get(3));

        // types are strings
        list.forEach(item -> assertTrue(item instanceof String, "Items must be strings: " + item + " (" + item.getClass() + ")"));
    }

    @Test
    public void inList_unquoted_numeric_is_string_for_refName() {
        String q = "refName:^[dd-test-1, 9987567]";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) f.getValue();
        assertEquals(2, list.size());
        assertEquals("dd-test-1", list.get(0));
        assertEquals("9987567", list.get(1));
        list.forEach(item -> assertTrue(item instanceof String));
    }

    @Test
    public void equality_unquoted_number_is_string_for_refName() {
        String q = "refName:9987567";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$eq", f.getName());
        assertEquals("refName", f.getField());
        assertTrue(f.getValue() instanceof String);
        assertEquals("9987567", f.getValue());
    }
}
