package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import dev.morphia.query.filters.Filter;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class QueryToFilterListenerInClauseTest {

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
    public void inList_preservesQuotedNumericString() {
        String q = "code:^[\"0123\"]"; // quoted -> must stay a string "0123"
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertEquals("$in", f.getName());
        assertEquals("code", f.getField());
        Object v = f.getValue();
        assertTrue(v instanceof List, "Value should be a List");
        List<?> list = (List<?>) v;
        assertEquals(1, list.size());
        assertTrue(list.get(0) instanceof String, "Quoted numeric should stay String");
        assertEquals("0123", list.get(0));
    }

    @Test
    public void inList_unquotedNumeric_becomesNumber() {
        String q = "code:^[0123]"; // unquoted -> coerced to number 123L
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertEquals("$in", f.getName());
        assertEquals("code", f.getField());
        Object v = f.getValue();
        assertTrue(v instanceof List);
        List<?> list = (List<?>) v;
        assertEquals(1, list.size());
        assertTrue(list.get(0) instanceof Number, "Unquoted numeric should become Number");
        assertEquals(123L, ((Number) list.get(0)).longValue());
    }

    @Test
    public void inList_mixedQuotedAndUnquoted() {
        String q = "code:^[\"012\",3,\"004\",5]";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertEquals("$in", f.getName());
        assertEquals("code", f.getField());
        Object v = f.getValue();
        assertTrue(v instanceof List);
        List<?> list = (List<?>) v;
        assertEquals(4, list.size());
        assertTrue(list.get(0) instanceof String);
        assertEquals("012", list.get(0));
        assertTrue(list.get(1) instanceof Number);
        assertEquals(3L, ((Number) list.get(1)).longValue());
        assertTrue(list.get(2) instanceof String);
        assertEquals("004", list.get(2));
        assertTrue(list.get(3) instanceof Number);
        assertEquals(5L, ((Number) list.get(3)).longValue());
    }

    @Test
    public void inList_variableOnly_mixedScalarsCollection() {
        String q = "code:^[${ids}]";
        Map<String, Object> extra = new HashMap<>();
        // Mixed scalars in object vars: numeric-looking strings will coerce to numbers; others remain strings
        extra.put("ids", Arrays.asList("012", 3L, "004", 5L, "X7"));
        MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(principal(), resource(), extra);

        Filter f = MorphiaUtils.convertToFilter(q, vars, DummyModel.class);
        assertEquals("$in", f.getName());
        assertEquals("code", f.getField());
        Object v = f.getValue();
        assertTrue(v instanceof List);
        List<?> list = (List<?>) v;
        assertEquals(5, list.size());
        // "012" -> 12L because collection values are coerced as scalars
        assertTrue(list.get(0) instanceof Number);
        assertEquals(12L, ((Number) list.get(0)).longValue());
        assertTrue(list.get(1) instanceof Number);
        assertEquals(3L, ((Number) list.get(1)).longValue());
        // "004" -> 4L for same reason
        assertTrue(list.get(2) instanceof Number);
        assertEquals(4L, ((Number) list.get(2)).longValue());
        assertTrue(list.get(3) instanceof Number);
        assertEquals(5L, ((Number) list.get(3)).longValue());
        // Non-numeric string remains string
        assertTrue(list.get(4) instanceof String);
        assertEquals("X7", list.get(4));
    }

    @Test
    public void inList_unresolvedVariable_throwsException() {
        // Test that an unresolved variable (not in objectVars or strings) throws an exception
        // rather than silently treating ${unresolvedVar} as a literal string
        String q = "locationId:^[${accessibleLocationIds}]";

        // Create a variable bundle that does NOT contain "accessibleLocationIds"
        Map<String, Object> extra = new HashMap<>();
        // Don't add accessibleLocationIds - simulating resolver's supports() returning false
        MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(principal(), resource(), extra);

        // Should throw IllegalStateException with helpful message about unresolved variable
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            MorphiaUtils.convertToFilter(q, vars, DummyModel.class);
        });

        assertTrue(ex.getMessage().contains("Unresolved resolver variable"),
                "Exception should mention unresolved variable");
        assertTrue(ex.getMessage().contains("accessibleLocationIds"),
                "Exception should mention the variable name");
        assertTrue(ex.getMessage().contains("locationId"),
                "Exception should mention the field name");
    }

    @Test
    public void inList_emptyCollection_createsEmptyInFilter() {
        // Test that an empty collection from a resolver creates an empty $in filter
        // (which matches no documents) rather than failing
        String q = "locationId:^[${accessibleLocationIds}]";

        Map<String, Object> extra = new HashMap<>();
        // Add an EMPTY collection - simulating resolver returning no access
        extra.put("accessibleLocationIds", Collections.emptyList());
        MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(principal(), resource(), extra);

        // Should succeed but create empty $in filter
        Filter f = MorphiaUtils.convertToFilter(q, vars, DummyModel.class);
        assertEquals("$in", f.getName());
        assertEquals("locationId", f.getField());
        Object v = f.getValue();
        assertTrue(v instanceof List);
        List<?> list = (List<?>) v;
        assertTrue(list.isEmpty(), "Empty collection should result in empty $in list");
    }

    @Test
    public void basicFilter_unresolvedVariable_throwsException() {
        // Test that unresolved variables in basic equality expressions also throw
        String q = "tenantId:${unresolvedTenant}";

        Map<String, Object> extra = new HashMap<>();
        MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(principal(), resource(), extra);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            MorphiaUtils.convertToFilter(q, vars, DummyModel.class);
        });

        assertTrue(ex.getMessage().contains("Unresolved resolver variable"),
                "Exception should mention unresolved variable");
        assertTrue(ex.getMessage().contains("unresolvedTenant"),
                "Exception should mention the variable name");
    }

    @Test
    public void inList_resolvedVariable_succeeds() {
        // Verify that properly resolved variables still work correctly
        String q = "customerId:^[${accessibleCustomerIds}]";

        Map<String, Object> extra = new HashMap<>();
        extra.put("accessibleCustomerIds", Arrays.asList(
                new ObjectId("5f1e1a5e5e5e5e5e5e5e5e51"),
                new ObjectId("5f1e1a5e5e5e5e5e5e5e5e52")
        ));
        MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(principal(), resource(), extra);

        Filter f = MorphiaUtils.convertToFilter(q, vars, DummyModel.class);
        assertEquals("$in", f.getName());
        assertEquals("customerId", f.getField());
        Object v = f.getValue();
        assertTrue(v instanceof List);
        List<?> list = (List<?>) v;
        assertEquals(2, list.size());
        assertTrue(list.get(0) instanceof ObjectId);
        assertTrue(list.get(1) instanceof ObjectId);
    }
}
