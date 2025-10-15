package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.query.filters.Filter;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueryToFilterListenerQueryCoercionTest {
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

    @Test
    public void equality_wholeNumber_coerces_to_Long_for_nonRefName() {
        String q = "version:#42"; // WHOLENUMBER → Long
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$eq", f.getName());
        assertEquals("version", f.getField());
        assertTrue(f.getValue() instanceof Long);
        assertEquals(42L, f.getValue());
    }

    @Test
    public void equality_number_coerces_to_Double_for_nonRefName() {
        String q = "score:##3.14"; // NUMBER → Double
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$eq", f.getName());
        assertEquals("score", f.getField());
        assertTrue(f.getValue() instanceof Double);
        assertEquals(3.14d, (Double) f.getValue(), 1e-9);
    }

    @Test
    public void equality_boolean_coerces_for_nonRefName() {
        String q = "active:TRUE";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$eq", f.getName());
        assertEquals("active", f.getField());
        assertTrue(f.getValue() instanceof Boolean);
        assertEquals(true, f.getValue());
    }

    @Test
    public void equality_dateToken_coerces_to_LocalDate_for_nonRefName() {
        String q = "createdDate:2024-10-01"; // DATE token → LocalDate
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$eq", f.getName());
        assertEquals("createdDate", f.getField());
        assertTrue(f.getValue() instanceof LocalDate);
        assertEquals(LocalDate.of(2024, 10, 1), f.getValue());
    }

    @Test
    public void equality_datetimeToken_coerces_to_Date_for_nonRefName() {
        String q = "createdAt:2024-10-01T12:34:56Z"; // DATETIME token → java.util.Date
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$eq", f.getName());
        assertEquals("createdAt", f.getField());
        assertTrue(f.getValue() instanceof java.util.Date);
        // (Optionally assert epoch millis if you want)
    }

    @Test
    public void equality_oidToken_coerces_to_ObjectId_for_nonRefName() {
        String id = "5f1e1a5e5e5e5e5e5e5e5e51";
        String q = "customerId:" + id; // OID token in your grammar
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$eq", f.getName());
        assertEquals("customerId", f.getField());
        assertTrue(f.getValue() instanceof ObjectId);
        assertEquals(id, ((ObjectId) f.getValue()).toHexString());
    }

    @Test
    public void variable_substitution_coerces_numeric_for_nonRefName() {
        String q = "version:${v}";

        // Build VariableBundle with a numeric string
        Map<String, String> stringVars = Map.of("v", "123");
        Map<String, Object> objectVars = Map.of();
        MorphiaUtils.VariableBundle bundle = new MorphiaUtils.VariableBundle(stringVars, objectVars);

        Filter f = MorphiaUtils.convertToFilter(q, bundle, DummyModel.class);

        assertEquals("$eq", f.getName());
        assertEquals("version", f.getField());
        // Not refName → coerceValueAuto applies → Long
        assertTrue(f.getValue() instanceof Long, "Expected Long but got " + f.getValue().getClass());
        assertEquals(123L, f.getValue());
    }

    @Test
    public void inList_unquoted_numbers_coerce_to_Long_for_nonRefName() {
        String q = "rank:^[1,2,3]";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$in", f.getName());
        assertEquals("rank", f.getField());

        Object v = f.getValue();
        assertTrue(v instanceof List);
        List<?> list = (List<?>) v;

        assertEquals(3, list.size());
        assertTrue(list.get(0) instanceof Long);
        assertEquals(1L, list.get(0));
        assertEquals(2L, list.get(1));
        assertEquals(3L, list.get(2));
    }

    @Test
    public void inList_mixed_numeric_tokens_preserve_types_for_nonRefName() {
        // WHOLENUMBER (→ Long), NUMBER (→ Double), and quoted (→ String)
        String q = "metric:^[42, 3.5, '0606']";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$in", f.getName());
        assertEquals("metric", f.getField());

        Object v = f.getValue();
        assertTrue(v instanceof List);
        List<?> list = (List<?>) v;

        assertEquals(3, list.size());
        assertTrue(list.get(0) instanceof Long);
        assertTrue(list.get(1) instanceof Double);
        assertTrue(list.get(2) instanceof String);

        assertEquals(42L, list.get(0));
        assertEquals(3.5d, (Double) list.get(1), 1e-9);
        assertEquals("0606", list.get(2)); // quoted → keep as string
    }
}