package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MongoDB $text operator validation.
 *
 * MongoDB requires $text to be a top-level query operator. It cannot be nested
 * inside $or, $not/$nor, or $elemMatch. These tests verify that the query parser
 * correctly rejects invalid text() usages at parse time rather than failing at
 * MongoDB runtime.
 *
 * Note: Due to grammar limitations with spaces in STRING tokens, some OR expressions
 * may fail to parse before the validation logic is reached. The validation catches
 * $text inside $or when the expression is properly parsed.
 */
public class QueryToFilterListenerTextValidationTest {

    // Minimal dummy model just to satisfy modelClass parameter
    public static class DummyModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "test-area"; }
        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    @Test
    public void testValidTextAtTopLevel() {
        // text() at top level should work
        String q = "text(\"search term\")";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertNotNull(f);
        assertEquals("$text", f.getName());
    }

    @Test
    public void testValidTextWithAndOperator() {
        // text() combined with AND should work (both are top-level in MongoDB)
        String q = "text(\"search term\") && status:active";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertNotNull(f);
        // Result should be an $and containing $text and $eq
        // Note: The actual filter structure depends on how the parser composes
        String filterStr = f.toString();
        assertTrue(filterStr.contains("$text") || "$text".equals(f.getName()) || "$and".equals(f.getName()),
            "Filter should be $text, $and, or contain $text: " + filterStr);
    }

    @Test
    public void testTextInsideNotThrows() {
        // text() inside NOT is not allowed by MongoDB
        String q = "!!text(\"foo\")";

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            MorphiaUtils.convertToFilter(q, DummyModel.class);
        });

        assertTrue(ex.getMessage().contains("text(...)"),
            "Error message should mention text(): " + ex.getMessage());
        assertTrue(ex.getMessage().contains("NOT") || ex.getMessage().contains("negat"),
            "Error message should mention NOT or negation: " + ex.getMessage());
    }

    @Test
    public void testTextInsideElemMatchThrows() {
        // text() inside elemMatch is not allowed by MongoDB
        String q = "tags:{text(\"foo\")}";

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            MorphiaUtils.convertToFilter(q, DummyModel.class);
        });

        assertTrue(ex.getMessage().contains("text(...)"),
            "Error message should mention text(): " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("elemmatch"),
            "Error message should mention elemMatch: " + ex.getMessage());
    }

    @Test
    public void testMultipleTextClausesThrow() {
        // Multiple text() clauses are not allowed by MongoDB
        String q = "text(\"foo\")&&text(\"bar\")";

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            MorphiaUtils.convertToFilter(q, DummyModel.class);
        });

        assertTrue(ex.getMessage().toLowerCase().contains("multiple"),
            "Error message should mention multiple text() clauses: " + ex.getMessage());
    }

    @Test
    public void testTextWithEmptySearchThrows() {
        // Empty text search should be rejected
        String q = "text(\"\")";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            MorphiaUtils.convertToFilter(q, DummyModel.class);
        });

        assertTrue(ex.getMessage().contains("non-empty") || ex.getMessage().toLowerCase().contains("empty"),
            "Error message should mention non-empty requirement: " + ex.getMessage());
    }

    @Test
    public void testTextWithQuotedSearchTerm() {
        // text() with quoted search term containing spaces should work
        String q = "text(\"hello world\")";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertNotNull(f);
        assertEquals("$text", f.getName());
    }

    @Test
    public void testTextWithSpecialCharacters() {
        // text() with special characters in search term should work
        String q = "text(\"test-query_123\")";
        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);
        assertNotNull(f);
        assertEquals("$text", f.getName());
    }
}
