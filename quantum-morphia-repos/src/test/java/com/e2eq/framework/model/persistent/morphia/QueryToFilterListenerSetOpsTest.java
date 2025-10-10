package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import dev.morphia.query.filters.Filter;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class QueryToFilterListenerSetOpsTest {

    // Minimal dummy model just to satisfy modelClass parameter
    public static class DummyModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "test-area"; }
        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
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
    public void testNinWithLiteralOids() {
        String q = "customerId:!^[5f1e1a5e5e5e5e5e5e5e5e51,5f1e1a5e5e5e5e5e5e5e5e52]";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        if ("$nin".equals(f.getName())) {
            assertEquals("customerId", f.getField());
            Object v = f.getValue();
            assertTrue(v instanceof List, "Value should be a List");
            List<?> list = (List<?>) v;
            assertEquals(2, list.size());
            assertTrue(list.get(0) instanceof ObjectId);
            assertEquals("5f1e1a5e5e5e5e5e5e5e5e51", ((ObjectId) list.get(0)).toHexString());
        } else {
            // Accept NOR wrapping an IN as a compatible semantic for NIN
            assertEquals("$nor", f.getName());
            String s = f.toString();
            assertTrue(s.contains("customerId"));
            assertTrue(s.contains("5f1e1a5e5e5e5e5e5e5e5e51"));
            assertTrue(s.contains("5f1e1a5e5e5e5e5e5e5e5e52"));
        }
    }

    @Test
    public void testNinWithVariableCommaSeparatedString() {
        String q = "customerId:!^[${ownerId}]";
        Map<String, Object> extra = new HashMap<>();
        MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(principal(), resource(), extra);
        // Insert into the strings map using an allowed VARIABLE name (ownerId) for CSV expansion
        vars.strings.put("ownerId", "5f1e1a5e5e5e5e5e5e5e5e51,5f1e1a5e5e5e5e5e5e5e5e52");

        Filter f = MorphiaUtils.convertToFilter(q, vars, DummyModel.class);
        if ("$nin".equals(f.getName())) {
            assertEquals("customerId", f.getField());
        } else {
            assertEquals("$nor", f.getName());
        }
        String s = f.toString();
        assertTrue(s.contains("5f1e1a5e5e5e5e5e5e5e5e51"));
        assertTrue(s.contains("5f1e1a5e5e5e5e5e5e5e5e52"));
    }

    @Test
    public void testNotWrapsEqualityWithNor() {
        String q = "status:active";
        Filter eq = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertEquals("$eq", eq.getName());
        assertEquals("status", eq.getField());

        String qNot = "!! status:active";
        Filter f = MorphiaUtils.convertToFilter(qNot, DummyModel.class);
        assertEquals("$nor", f.getName());
        // Field is typically null for $nor wrapper; ensure the inner expression shows in string form
        String s = f.toString();
        assertTrue(s.contains("status"));
        assertTrue(s.contains("active"));
    }

    @Test
    public void testNotWrapsRegexWithNor() {
        String q = "name:*alpha*"; // translates to a regex
        Filter f = MorphiaUtils.convertToFilter("!! " + q, DummyModel.class);
        assertEquals("$nor", f.getName());
        String s = f.toString();
        assertTrue(s.contains("name"));
        assertTrue(s.toLowerCase().contains("regex") || s.contains(".*alpha.*"));
    }

    @Test
    public void testNotWrapsInClauseWithNor() {
        String q = "!! customerId:^[5f1e1a5e5e5e5e5e5e5e5e51,5f1e1a5e5e5e5e5e5e5e5e52]";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertEquals("$nor", f.getName());
        String s = f.toString();
        assertTrue(s.contains("customerId"));
        // should contain the OIDs inside the NOR string representation
        assertTrue(s.contains("5f1e1a5e5e5e5e5e5e5e5e51"));
        assertTrue(s.contains("5f1e1a5e5e5e5e5e5e5e5e52"));
    }
}
