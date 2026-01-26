package com.e2eq.framework.imports.dynamic;

import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import com.e2eq.framework.model.persistent.imports.DynamicAttributeImportStrategy;
import com.e2eq.framework.model.persistent.imports.ImportProfile;
import com.e2eq.framework.model.persistent.imports.ParsedDynamicHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DotNotationProcessor.
 */
class DotNotationProcessorTest {

    private DotNotationProcessor processor;
    private ImportProfile profile;

    @BeforeEach
    void setUp() {
        processor = new DotNotationProcessor();
        // Manually inject the type inferrer since we're not using CDI
        processor.typeInferrer = new DynamicAttributeTypeInferrer();

        // Create a basic profile with DOT_NOTATION strategy
        profile = ImportProfile.builder()
                .refName("test-profile")
                .targetCollection("com.example.TestEntity")
                .dynamicAttributeStrategy(DynamicAttributeImportStrategy.DOT_NOTATION)
                .build();
    }

    // ==================== Header Parsing Tests ====================

    @Test
    void parseHeader_ValidFormat() {
        ParsedDynamicHeader result = processor.parseHeader("dyn.custom.color", "dyn.", profile);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertEquals("dyn.custom.color", result.getOriginalHeader());
        assertEquals("custom", result.getSetName());
        assertEquals("color", result.getAttributeName());
        assertNull(result.getType()); // No type specified
    }

    @Test
    void parseHeader_WithTypeHint() {
        ParsedDynamicHeader result = processor.parseHeader("dyn.specs.weight:double", "dyn.", profile);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertEquals("specs", result.getSetName());
        assertEquals("weight", result.getAttributeName());
        assertEquals(DynamicAttributeType.Double, result.getType());
    }

    @Test
    void parseHeader_WithIntegerType() {
        ParsedDynamicHeader result = processor.parseHeader("dyn.metrics.count:int", "dyn.", profile);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertEquals("metrics", result.getSetName());
        assertEquals("count", result.getAttributeName());
        assertEquals(DynamicAttributeType.Integer, result.getType());
    }

    @Test
    void parseHeader_WithBooleanType() {
        ParsedDynamicHeader result = processor.parseHeader("dyn.flags.active:bool", "dyn.", profile);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertEquals("flags", result.getSetName());
        assertEquals("active", result.getAttributeName());
        assertEquals(DynamicAttributeType.Boolean, result.getType());
    }

    @Test
    void parseHeader_InvalidFormat_MissingSetName() {
        ParsedDynamicHeader result = processor.parseHeader("dyn.color", "dyn.", profile);

        assertNotNull(result);
        assertFalse(result.isValid());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void parseHeader_InvalidFormat_EmptyAfterPrefix() {
        ParsedDynamicHeader result = processor.parseHeader("dyn.", "dyn.", profile);

        assertNotNull(result);
        assertFalse(result.isValid());
    }

    @Test
    void parseHeader_NonMatchingPrefix() {
        ParsedDynamicHeader result = processor.parseHeader("other.custom.color", "dyn.", profile);

        assertNull(result);
    }

    @Test
    void parseHeader_NullHeader() {
        ParsedDynamicHeader result = processor.parseHeader(null, "dyn.", profile);

        assertNull(result);
    }

    // ==================== Parse Headers Tests ====================

    @Test
    void parseHeaders_MultipleColumns() {
        String[] headers = {"id", "name", "dyn.custom.color", "dyn.specs.weight:double", "status"};

        Map<Integer, ParsedDynamicHeader> result = processor.parseHeaders(headers, profile);

        assertEquals(2, result.size());
        assertTrue(result.containsKey(2)); // dyn.custom.color
        assertTrue(result.containsKey(3)); // dyn.specs.weight
        assertEquals("custom", result.get(2).getSetName());
        assertEquals("specs", result.get(3).getSetName());
    }

    @Test
    void parseHeaders_NoDynamicColumns() {
        String[] headers = {"id", "name", "status"};

        Map<Integer, ParsedDynamicHeader> result = processor.parseHeaders(headers, profile);

        assertTrue(result.isEmpty());
    }

    // ==================== Process Row Tests ====================

    @Test
    void processRow_SingleAttribute() {
        String[] headers = {"id", "dyn.custom.color"};
        Map<Integer, ParsedDynamicHeader> parsedHeaders = processor.parseHeaders(headers, profile);

        Map<String, Object> rowData = new HashMap<>();
        rowData.put("id", "123");
        rowData.put("dyn.custom.color", "red");

        Map<String, DynamicAttributeSet> result = processor.processRow(rowData, parsedHeaders, headers, profile);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("custom"));

        DynamicAttributeSet customSet = result.get("custom");
        assertEquals("custom", customSet.getName());
        assertEquals(1, customSet.getAttributes().size());
        assertEquals("color", customSet.getAttributes().get(0).getName());
        assertEquals("red", customSet.getAttributes().get(0).getValue());
    }

    @Test
    void processRow_MultipleAttributesSameSet() {
        String[] headers = {"id", "dyn.custom.color", "dyn.custom.size"};
        Map<Integer, ParsedDynamicHeader> parsedHeaders = processor.parseHeaders(headers, profile);

        Map<String, Object> rowData = new HashMap<>();
        rowData.put("id", "123");
        rowData.put("dyn.custom.color", "red");
        rowData.put("dyn.custom.size", "large");

        Map<String, DynamicAttributeSet> result = processor.processRow(rowData, parsedHeaders, headers, profile);

        assertEquals(1, result.size());

        DynamicAttributeSet customSet = result.get("custom");
        assertEquals(2, customSet.getAttributes().size());
    }

    @Test
    void processRow_MultipleAttributeSets() {
        String[] headers = {"id", "dyn.custom.color", "dyn.specs.weight:double"};
        Map<Integer, ParsedDynamicHeader> parsedHeaders = processor.parseHeaders(headers, profile);

        Map<String, Object> rowData = new HashMap<>();
        rowData.put("id", "123");
        rowData.put("dyn.custom.color", "red");
        rowData.put("dyn.specs.weight:double", "2.5");

        Map<String, DynamicAttributeSet> result = processor.processRow(rowData, parsedHeaders, headers, profile);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("custom"));
        assertTrue(result.containsKey("specs"));

        assertEquals(2.5, result.get("specs").getAttributes().get(0).getValue());
        assertEquals(DynamicAttributeType.Double, result.get("specs").getAttributes().get(0).getType());
    }

    @Test
    void processRow_TypeInference_Integer() {
        String[] headers = {"id", "dyn.metrics.count"};
        Map<Integer, ParsedDynamicHeader> parsedHeaders = processor.parseHeaders(headers, profile);

        Map<String, Object> rowData = new HashMap<>();
        rowData.put("id", "123");
        rowData.put("dyn.metrics.count", "42");

        Map<String, DynamicAttributeSet> result = processor.processRow(rowData, parsedHeaders, headers, profile);

        assertEquals(DynamicAttributeType.Integer, result.get("metrics").getAttributes().get(0).getType());
        assertEquals(42, result.get("metrics").getAttributes().get(0).getValue());
    }

    @Test
    void processRow_SkipsNullValues() {
        String[] headers = {"id", "dyn.custom.color"};
        Map<Integer, ParsedDynamicHeader> parsedHeaders = processor.parseHeaders(headers, profile);

        Map<String, Object> rowData = new HashMap<>();
        rowData.put("id", "123");
        rowData.put("dyn.custom.color", null);

        Map<String, DynamicAttributeSet> result = processor.processRow(rowData, parsedHeaders, headers, profile);

        assertTrue(result.isEmpty());
    }

    @Test
    void processRow_SkipsEmptyValues() {
        String[] headers = {"id", "dyn.custom.color"};
        Map<Integer, ParsedDynamicHeader> parsedHeaders = processor.parseHeaders(headers, profile);

        Map<String, Object> rowData = new HashMap<>();
        rowData.put("id", "123");
        rowData.put("dyn.custom.color", "   ");

        Map<String, DynamicAttributeSet> result = processor.processRow(rowData, parsedHeaders, headers, profile);

        assertTrue(result.isEmpty());
    }

    // ==================== Helper Method Tests ====================

    @Test
    void hasDynamicColumns_True() {
        String[] headers = {"id", "dyn.custom.color", "name"};

        assertTrue(processor.hasDynamicColumns(headers, profile));
    }

    @Test
    void hasDynamicColumns_False() {
        String[] headers = {"id", "name", "status"};

        assertFalse(processor.hasDynamicColumns(headers, profile));
    }

    @Test
    void getDynamicColumnNames() {
        String[] headers = {"id", "dyn.custom.color", "name", "dyn.specs.weight"};

        var result = processor.getDynamicColumnNames(headers, profile);

        assertEquals(2, result.size());
        assertTrue(result.contains("dyn.custom.color"));
        assertTrue(result.contains("dyn.specs.weight"));
    }
}
