package com.e2eq.framework.imports;

import com.e2eq.framework.imports.processors.CaseTransformProcessor;
import com.e2eq.framework.imports.processors.RegexReplaceProcessor;
import com.e2eq.framework.imports.processors.ValueMapProcessor;
import com.e2eq.framework.model.persistent.imports.*;
import org.junit.jupiter.api.Test;
import org.supercsv.cellprocessor.Optional;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.util.CsvContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImportProfile components.
 */
public class ImportProfileTest {

    @Test
    void testHeaderModifierParsing() {
        // Test required modifier
        ParsedHeader required = ParsedHeader.parse("displayName*", true);
        assertEquals("displayName", required.getFieldName());
        assertEquals(HeaderModifier.REQUIRED, required.getModifier());
        assertTrue(required.isRequired());

        // Test optional modifier
        ParsedHeader optional = ParsedHeader.parse("description?", true);
        assertEquals("description", optional.getFieldName());
        assertEquals(HeaderModifier.OPTIONAL, optional.getModifier());
        assertTrue(optional.isOptional());

        // Test calculated modifier
        ParsedHeader calculated = ParsedHeader.parse("createdAt~", true);
        assertEquals("createdAt", calculated.getFieldName());
        assertEquals(HeaderModifier.CALCULATED, calculated.getModifier());
        assertTrue(calculated.isCalculated());

        // Test key modifier
        ParsedHeader key = ParsedHeader.parse("sku#", true);
        assertEquals("sku", key.getFieldName());
        assertEquals(HeaderModifier.KEY, key.getModifier());
        assertTrue(key.isKey());

        // Test no modifier
        ParsedHeader none = ParsedHeader.parse("refName", true);
        assertEquals("refName", none.getFieldName());
        assertEquals(HeaderModifier.NONE, none.getModifier());

        // Test disabled modifiers
        ParsedHeader disabled = ParsedHeader.parse("displayName*", false);
        assertEquals("displayName*", disabled.getFieldName());
        assertEquals(HeaderModifier.NONE, disabled.getModifier());
    }

    @Test
    void testValueMapProcessor() {
        Map<String, String> mappings = new HashMap<>();
        mappings.put("Y", "true");
        mappings.put("N", "false");
        mappings.put("YES", "true");
        mappings.put("NO", "false");

        CellProcessor processor = new ValueMapProcessor(mappings, false,
                UnmappedValueBehavior.PASSTHROUGH, new Optional());

        CsvContext ctx = new CsvContext(1, 1, 1);

        // Test case-insensitive mapping
        assertEquals("true", processor.execute("Y", ctx));
        assertEquals("true", processor.execute("y", ctx));
        assertEquals("false", processor.execute("N", ctx));
        assertEquals("true", processor.execute("yes", ctx));
        assertEquals("false", processor.execute("NO", ctx));

        // Test passthrough for unmapped values
        assertEquals("UNKNOWN", processor.execute("UNKNOWN", ctx));
    }

    @Test
    void testValueMapProcessorCaseSensitive() {
        Map<String, String> mappings = new HashMap<>();
        mappings.put("Y", "true");
        mappings.put("N", "false");

        CellProcessor processor = new ValueMapProcessor(mappings, true,
                UnmappedValueBehavior.PASSTHROUGH, new Optional());

        CsvContext ctx = new CsvContext(1, 1, 1);

        // Test case-sensitive mapping
        assertEquals("true", processor.execute("Y", ctx));
        assertEquals("y", processor.execute("y", ctx)); // Not mapped, passthrough
    }

    @Test
    void testRegexReplaceProcessor() {
        // Remove currency symbols
        CellProcessor processor = new RegexReplaceProcessor("[$,]", "", new Optional());
        CsvContext ctx = new CsvContext(1, 1, 1);

        assertEquals("19.99", processor.execute("$19.99", ctx));
        assertEquals("1000.00", processor.execute("$1,000.00", ctx));

        // Extract numbers only
        CellProcessor numbersOnly = new RegexReplaceProcessor("[^0-9]", "", new Optional());
        assertEquals("1234567890", numbersOnly.execute("(123) 456-7890", ctx));
    }

    @Test
    void testCaseTransformProcessor() {
        CsvContext ctx = new CsvContext(1, 1, 1);

        // Test UPPER
        CellProcessor upper = new CaseTransformProcessor(CaseTransform.UPPER, new Optional());
        assertEquals("HELLO WORLD", upper.execute("hello world", ctx));

        // Test LOWER
        CellProcessor lower = new CaseTransformProcessor(CaseTransform.LOWER, new Optional());
        assertEquals("hello world", lower.execute("HELLO WORLD", ctx));

        // Test TITLE
        CellProcessor title = new CaseTransformProcessor(CaseTransform.TITLE, new Optional());
        assertEquals("Hello World", title.execute("hello world", ctx));
        assertEquals("John Smith", title.execute("JOHN SMITH", ctx));

        // Test NONE
        CellProcessor none = new CaseTransformProcessor(CaseTransform.NONE, new Optional());
        assertEquals("HeLLo WoRLd", none.execute("HeLLo WoRLd", ctx));
    }

    @Test
    void testImportProfileBuilder() {
        ImportProfile profile = ImportProfile.builder()
                .refName("test-profile")
                .displayName("Test Profile")
                .targetCollection("com.example.Product")
                .enableHeaderModifiers(true)
                .defaultIntent(ImportIntent.UPSERT)
                .build();

        assertEquals("test-profile", profile.getRefName());
        assertEquals("com.example.Product", profile.getTargetCollection());
        assertTrue(profile.isEnableHeaderModifiers());
        assertEquals(ImportIntent.UPSERT, profile.getDefaultIntent());
    }

    @Test
    void testColumnMappingBuilder() {
        Map<String, String> valueMappings = new HashMap<>();
        valueMappings.put("A", "ACTIVE");
        valueMappings.put("I", "INACTIVE");

        ColumnMapping mapping = ColumnMapping.builder()
                .sourceColumn("status")
                .targetField("activeStatus")
                .valueMappings(valueMappings)
                .valueMappingCaseSensitive(false)
                .unmappedValueBehavior(UnmappedValueBehavior.FAIL)
                .trim(true)
                .caseTransform(CaseTransform.UPPER)
                .build();

        assertEquals("status", mapping.getSourceColumn());
        assertEquals("activeStatus", mapping.getTargetField());
        assertEquals(2, mapping.getValueMappings().size());
        assertFalse(mapping.isValueMappingCaseSensitive());
        assertEquals(UnmappedValueBehavior.FAIL, mapping.getUnmappedValueBehavior());
        assertTrue(mapping.isTrim());
        assertEquals(CaseTransform.UPPER, mapping.getCaseTransform());
    }

    @Test
    void testLookupConfigBuilder() {
        LookupConfig lookup = LookupConfig.builder()
                .lookupCollection("Category")
                .lookupMatchField("displayName")
                .lookupReturnField("refName")
                .onNotFound(LookupFailBehavior.NULL)
                .cacheLookups(true)
                .lookupFilter("status='ACTIVE'")
                .build();

        assertEquals("Category", lookup.getLookupCollection());
        assertEquals("displayName", lookup.getLookupMatchField());
        assertEquals("refName", lookup.getLookupReturnField());
        assertEquals(LookupFailBehavior.NULL, lookup.getOnNotFound());
        assertTrue(lookup.isCacheLookups());
        assertEquals("status='ACTIVE'", lookup.getLookupFilter());
    }

    @Test
    void testGlobalTransformationsDefaults() {
        GlobalTransformations global = GlobalTransformations.builder().build();

        assertTrue(global.isTrimStrings());
        assertTrue(global.isEmptyStringsToNull());
        assertFalse(global.isRemoveControlChars());
        assertFalse(global.isNormalizeWhitespace());
        assertNull(global.getUnicodeNormalization());
        assertNull(global.getMaxStringLength());
    }

    @Test
    void testInlineFieldCalculator() {
        InlineFieldCalculator timestamp = InlineFieldCalculator.builder()
                .fieldName("createdAt")
                .type(InlineFieldCalculator.CalculationType.TIMESTAMP)
                .build();

        assertEquals("createdAt", timestamp.getFieldName());
        assertEquals(InlineFieldCalculator.CalculationType.TIMESTAMP, timestamp.getType());

        InlineFieldCalculator uuid = InlineFieldCalculator.builder()
                .fieldName("externalId")
                .type(InlineFieldCalculator.CalculationType.UUID)
                .build();

        assertEquals("externalId", uuid.getFieldName());
        assertEquals(InlineFieldCalculator.CalculationType.UUID, uuid.getType());

        InlineFieldCalculator staticValue = InlineFieldCalculator.builder()
                .fieldName("source")
                .type(InlineFieldCalculator.CalculationType.STATIC)
                .staticValue("CSV_IMPORT")
                .build();

        assertEquals("CSV_IMPORT", staticValue.getStaticValue());

        InlineFieldCalculator copy = InlineFieldCalculator.builder()
                .fieldName("refName")
                .type(InlineFieldCalculator.CalculationType.COPY)
                .sourceField("sku")
                .build();

        assertEquals("sku", copy.getSourceField());

        InlineFieldCalculator template = InlineFieldCalculator.builder()
                .fieldName("fullName")
                .type(InlineFieldCalculator.CalculationType.TEMPLATE)
                .template("${firstName} ${lastName}")
                .build();

        assertEquals("${firstName} ${lastName}", template.getTemplate());
    }

    @Test
    void testImportIntent() {
        // Test all values
        assertEquals(ImportIntent.INSERT, ImportIntent.valueOf("INSERT"));
        assertEquals(ImportIntent.UPDATE, ImportIntent.valueOf("UPDATE"));
        assertEquals(ImportIntent.SKIP, ImportIntent.valueOf("SKIP"));
        assertEquals(ImportIntent.UPSERT, ImportIntent.valueOf("UPSERT"));

        // Verify no MERGE or DELETE
        assertThrows(IllegalArgumentException.class, () -> ImportIntent.valueOf("MERGE"));
        assertThrows(IllegalArgumentException.class, () -> ImportIntent.valueOf("DELETE"));
    }

    @Test
    void testHeaderModifierFromSuffix() {
        assertEquals(HeaderModifier.REQUIRED, HeaderModifier.fromSuffix("*"));
        assertEquals(HeaderModifier.OPTIONAL, HeaderModifier.fromSuffix("?"));
        assertEquals(HeaderModifier.CALCULATED, HeaderModifier.fromSuffix("~"));
        assertEquals(HeaderModifier.KEY, HeaderModifier.fromSuffix("#"));
        assertEquals(HeaderModifier.NONE, HeaderModifier.fromSuffix(""));
        assertEquals(HeaderModifier.NONE, HeaderModifier.fromSuffix("x"));
        assertEquals(HeaderModifier.NONE, HeaderModifier.fromSuffix(null));
    }

    @Test
    void testHeaderModifierIsModifierChar() {
        assertTrue(HeaderModifier.isModifierChar('*'));
        assertTrue(HeaderModifier.isModifierChar('?'));
        assertTrue(HeaderModifier.isModifierChar('~'));
        assertTrue(HeaderModifier.isModifierChar('#'));
        assertFalse(HeaderModifier.isModifierChar('!'));
        assertFalse(HeaderModifier.isModifierChar('a'));
        assertFalse(HeaderModifier.isModifierChar(' '));
    }

    @Test
    void testColumnMappingWithRowValueResolver() {
        ColumnMapping mapping = ColumnMapping.builder()
                .sourceColumn("product_code")
                .targetField("sku")
                .rowValueResolverName("skuResolver")
                .build();

        assertEquals("product_code", mapping.getSourceColumn());
        assertEquals("sku", mapping.getTargetField());
        assertEquals("skuResolver", mapping.getRowValueResolverName());
    }

    @Test
    void testRowValueResolverResult() {
        // Test success result
        com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult success =
                com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult.success("resolved-value");
        assertTrue(success.isSuccess());
        assertFalse(success.isSkip());
        assertEquals("resolved-value", success.getValue());
        assertNull(success.getErrorMessage());

        // Test passthrough result
        com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult passthrough =
                com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult.passthrough("original");
        assertTrue(passthrough.isSuccess());
        assertEquals("original", passthrough.getValue());

        // Test null result
        com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult nullResult =
                com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult.nullValue();
        assertTrue(nullResult.isSuccess());
        assertNull(nullResult.getValue());

        // Test skip result
        com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult skip =
                com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult.skip("duplicate record");
        assertTrue(skip.isSuccess());
        assertTrue(skip.isSkip());
        assertEquals("duplicate record", skip.getErrorMessage());

        // Test error result
        com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult error =
                com.e2eq.framework.imports.spi.RowValueResolver.ResolveResult.error("lookup failed");
        assertFalse(error.isSuccess());
        assertFalse(error.isSkip());
        assertEquals("lookup failed", error.getErrorMessage());
    }
}
