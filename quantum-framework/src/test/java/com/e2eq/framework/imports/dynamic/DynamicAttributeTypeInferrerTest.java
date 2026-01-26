package com.e2eq.framework.imports.dynamic;

import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DynamicAttributeTypeInferrer.
 */
class DynamicAttributeTypeInferrerTest {

    private DynamicAttributeTypeInferrer inferrer;

    @BeforeEach
    void setUp() {
        inferrer = new DynamicAttributeTypeInferrer();
    }

    // ==================== Type Inference Tests ====================

    @Test
    void inferType_Boolean_True() {
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("true"));
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("TRUE"));
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("True"));
    }

    @Test
    void inferType_Boolean_False() {
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("false"));
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("FALSE"));
    }

    @Test
    void inferType_Boolean_YesNo() {
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("yes"));
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("no"));
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("y"));
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("n"));
    }

    @Test
    void inferType_Boolean_OneZero() {
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("1"));
        assertEquals(DynamicAttributeType.Boolean, inferrer.inferType("0"));
    }

    @Test
    void inferType_Integer() {
        assertEquals(DynamicAttributeType.Integer, inferrer.inferType("42"));
        assertEquals(DynamicAttributeType.Integer, inferrer.inferType("-123"));
        assertEquals(DynamicAttributeType.Integer, inferrer.inferType("999999999"));
    }

    @Test
    void inferType_Long() {
        assertEquals(DynamicAttributeType.Long, inferrer.inferType("1234567890123"));
        assertEquals(DynamicAttributeType.Long, inferrer.inferType("-9876543210123"));
    }

    @Test
    void inferType_Double() {
        assertEquals(DynamicAttributeType.Double, inferrer.inferType("3.14"));
        assertEquals(DynamicAttributeType.Double, inferrer.inferType("-2.5"));
        assertEquals(DynamicAttributeType.Double, inferrer.inferType("0.001"));
    }

    @Test
    void inferType_Float() {
        assertEquals(DynamicAttributeType.Float, inferrer.inferType("3.14f"));
        assertEquals(DynamicAttributeType.Float, inferrer.inferType("2.5F"));
    }

    @Test
    void inferType_Date_IsoFormat() {
        assertEquals(DynamicAttributeType.Date, inferrer.inferType("2024-01-15"));
    }

    @Test
    void inferType_Date_UsFormat() {
        assertEquals(DynamicAttributeType.Date, inferrer.inferType("01/15/2024"));
    }

    @Test
    void inferType_DateTime_IsoFormat() {
        assertEquals(DynamicAttributeType.DateTime, inferrer.inferType("2024-01-15T10:30:00"));
    }

    @Test
    void inferType_DateTime_SpaceFormat() {
        assertEquals(DynamicAttributeType.DateTime, inferrer.inferType("2024-01-15 10:30:00"));
    }

    @Test
    void inferType_String_Default() {
        assertEquals(DynamicAttributeType.String, inferrer.inferType("hello world"));
        assertEquals(DynamicAttributeType.String, inferrer.inferType("abc123"));
    }

    @Test
    void inferType_Null_ReturnsDefault() {
        assertEquals(DynamicAttributeType.String, inferrer.inferType(null));
        assertEquals(DynamicAttributeType.String, inferrer.inferType(""));
        assertEquals(DynamicAttributeType.String, inferrer.inferType("   "));
    }

    // ==================== Type Hint Parsing Tests ====================

    @Test
    void parseTypeHint_String() {
        assertEquals(DynamicAttributeType.String, inferrer.parseTypeHint("string"));
        assertEquals(DynamicAttributeType.String, inferrer.parseTypeHint("str"));
        assertEquals(DynamicAttributeType.String, inferrer.parseTypeHint("s"));
    }

    @Test
    void parseTypeHint_Integer() {
        assertEquals(DynamicAttributeType.Integer, inferrer.parseTypeHint("integer"));
        assertEquals(DynamicAttributeType.Integer, inferrer.parseTypeHint("int"));
        assertEquals(DynamicAttributeType.Integer, inferrer.parseTypeHint("i"));
    }

    @Test
    void parseTypeHint_Long() {
        assertEquals(DynamicAttributeType.Long, inferrer.parseTypeHint("long"));
        assertEquals(DynamicAttributeType.Long, inferrer.parseTypeHint("l"));
    }

    @Test
    void parseTypeHint_Double() {
        assertEquals(DynamicAttributeType.Double, inferrer.parseTypeHint("double"));
        assertEquals(DynamicAttributeType.Double, inferrer.parseTypeHint("d"));
        assertEquals(DynamicAttributeType.Double, inferrer.parseTypeHint("decimal"));
    }

    @Test
    void parseTypeHint_Boolean() {
        assertEquals(DynamicAttributeType.Boolean, inferrer.parseTypeHint("boolean"));
        assertEquals(DynamicAttributeType.Boolean, inferrer.parseTypeHint("bool"));
        assertEquals(DynamicAttributeType.Boolean, inferrer.parseTypeHint("b"));
    }

    @Test
    void parseTypeHint_Date() {
        assertEquals(DynamicAttributeType.Date, inferrer.parseTypeHint("date"));
    }

    @Test
    void parseTypeHint_DateTime() {
        assertEquals(DynamicAttributeType.DateTime, inferrer.parseTypeHint("datetime"));
        assertEquals(DynamicAttributeType.DateTime, inferrer.parseTypeHint("timestamp"));
        assertEquals(DynamicAttributeType.DateTime, inferrer.parseTypeHint("ts"));
    }

    @Test
    void parseTypeHint_Select() {
        assertEquals(DynamicAttributeType.Select, inferrer.parseTypeHint("select"));
    }

    @Test
    void parseTypeHint_MultiSelect() {
        assertEquals(DynamicAttributeType.MultiSelect, inferrer.parseTypeHint("multiselect"));
        assertEquals(DynamicAttributeType.MultiSelect, inferrer.parseTypeHint("multi"));
    }

    @Test
    void parseTypeHint_Unknown_ReturnsNull() {
        assertNull(inferrer.parseTypeHint("unknown"));
        assertNull(inferrer.parseTypeHint(""));
        assertNull(inferrer.parseTypeHint(null));
    }

    // ==================== Value Conversion Tests ====================

    @Test
    void convertValue_Integer() {
        assertEquals(42, inferrer.convertValue("42", DynamicAttributeType.Integer, null, null));
        assertEquals(-123, inferrer.convertValue("-123", DynamicAttributeType.Integer, null, null));
    }

    @Test
    void convertValue_Long() {
        assertEquals(1234567890123L, inferrer.convertValue("1234567890123", DynamicAttributeType.Long, null, null));
    }

    @Test
    void convertValue_Double() {
        assertEquals(3.14, inferrer.convertValue("3.14", DynamicAttributeType.Double, null, null));
    }

    @Test
    void convertValue_Boolean() {
        assertEquals(true, inferrer.convertValue("true", DynamicAttributeType.Boolean, null, null));
        assertEquals(false, inferrer.convertValue("false", DynamicAttributeType.Boolean, null, null));
        assertEquals(true, inferrer.convertValue("yes", DynamicAttributeType.Boolean, null, null));
        assertEquals(false, inferrer.convertValue("no", DynamicAttributeType.Boolean, null, null));
    }

    @Test
    void convertValue_Date() {
        Object result = inferrer.convertValue("2024-01-15", DynamicAttributeType.Date, null, null);
        assertInstanceOf(LocalDate.class, result);
        assertEquals(LocalDate.of(2024, 1, 15), result);
    }

    @Test
    void convertValue_DateTime() {
        Object result = inferrer.convertValue("2024-01-15T10:30:00", DynamicAttributeType.DateTime, null, null);
        assertInstanceOf(LocalDateTime.class, result);
        assertEquals(LocalDateTime.of(2024, 1, 15, 10, 30, 0), result);
    }

    @Test
    void convertValue_String() {
        assertEquals("hello", inferrer.convertValue("hello", DynamicAttributeType.String, null, null));
    }

    @Test
    void convertValue_Null() {
        assertNull(inferrer.convertValue(null, DynamicAttributeType.String, null, null));
        assertNull(inferrer.convertValue("", DynamicAttributeType.String, null, null));
    }

    @Test
    void convertValue_Exclude_ReturnsNull() {
        assertNull(inferrer.convertValue("anything", DynamicAttributeType.Exclude, null, null));
    }

    @Test
    void convertValue_WithCustomDateFormat() {
        Object result = inferrer.convertValue("15/01/2024", DynamicAttributeType.Date, "dd/MM/yyyy", null);
        assertInstanceOf(LocalDate.class, result);
        assertEquals(LocalDate.of(2024, 1, 15), result);
    }
}
