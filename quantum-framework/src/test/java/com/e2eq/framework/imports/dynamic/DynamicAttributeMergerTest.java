package com.e2eq.framework.imports.dynamic;

import com.e2eq.framework.model.persistent.base.DynamicAttribute;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import com.e2eq.framework.model.persistent.imports.DynamicAttributeMergeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DynamicAttributeMerger.
 */
class DynamicAttributeMergerTest {

    private DynamicAttributeMerger merger;

    @BeforeEach
    void setUp() {
        merger = new DynamicAttributeMerger();
    }

    // ==================== Helper Methods ====================

    private DynamicAttributeSet createSet(String name, DynamicAttribute... attributes) {
        DynamicAttributeSet set = new DynamicAttributeSet();
        set.setName(name);
        set.setAttributes(new ArrayList<>(Arrays.asList(attributes)));
        return set;
    }

    private DynamicAttribute createAttr(String name, Object value, DynamicAttributeType type) {
        return DynamicAttribute.builder()
                .name(name)
                .value(value)
                .type(type)
                .build();
    }

    // ==================== REPLACE Strategy Tests ====================

    @Test
    void merge_Replace_NewSetAdded() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String))
        );

        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setB", createSet("setB", createAttr("attr2", "value2", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(existing, imported, DynamicAttributeMergeStrategy.REPLACE);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> "setA".equals(s.getName())));
        assertTrue(result.stream().anyMatch(s -> "setB".equals(s.getName())));
    }

    @Test
    void merge_Replace_ExistingSetReplaced() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA",
                        createAttr("attr1", "oldValue", DynamicAttributeType.String),
                        createAttr("attr2", "otherValue", DynamicAttributeType.String))
        );

        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setA", createSet("setA", createAttr("attr1", "newValue", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(existing, imported, DynamicAttributeMergeStrategy.REPLACE);

        assertEquals(1, result.size());
        DynamicAttributeSet setA = result.get(0);
        assertEquals(1, setA.getAttributes().size()); // Only imported attr, existing removed
        assertEquals("newValue", setA.getAttributes().get(0).getValue());
    }

    // ==================== MERGE Strategy Tests ====================

    @Test
    void merge_Merge_NewSetAdded() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String))
        );

        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setB", createSet("setB", createAttr("attr2", "value2", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(existing, imported, DynamicAttributeMergeStrategy.MERGE);

        assertEquals(2, result.size());
    }

    @Test
    void merge_Merge_AttributeseMerged() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String))
        );

        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setA", createSet("setA", createAttr("attr2", "value2", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(existing, imported, DynamicAttributeMergeStrategy.MERGE);

        assertEquals(1, result.size());
        DynamicAttributeSet setA = result.get(0);
        assertEquals(2, setA.getAttributes().size()); // Both attrs present
        assertTrue(setA.getAttributes().stream().anyMatch(a -> "attr1".equals(a.getName())));
        assertTrue(setA.getAttributes().stream().anyMatch(a -> "attr2".equals(a.getName())));
    }

    @Test
    void merge_Merge_MatchingAttributeOverwritten() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "oldValue", DynamicAttributeType.String))
        );

        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setA", createSet("setA", createAttr("attr1", "newValue", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(existing, imported, DynamicAttributeMergeStrategy.MERGE);

        assertEquals(1, result.size());
        DynamicAttributeSet setA = result.get(0);
        assertEquals(1, setA.getAttributes().size());
        assertEquals("newValue", setA.getAttributes().get(0).getValue());
    }

    // ==================== APPEND Strategy Tests ====================

    @Test
    void merge_Append_NewSetAdded() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String))
        );

        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setB", createSet("setB", createAttr("attr2", "value2", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(existing, imported, DynamicAttributeMergeStrategy.APPEND);

        assertEquals(2, result.size());
    }

    @Test
    void merge_Append_AttributesAppended() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String))
        );

        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setA", createSet("setA", createAttr("attr2", "value2", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(existing, imported, DynamicAttributeMergeStrategy.APPEND);

        assertEquals(1, result.size());
        DynamicAttributeSet setA = result.get(0);
        assertEquals(2, setA.getAttributes().size());
    }

    @Test
    void merge_Append_DuplicatesAllowed() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "oldValue", DynamicAttributeType.String))
        );

        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setA", createSet("setA", createAttr("attr1", "newValue", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(existing, imported, DynamicAttributeMergeStrategy.APPEND);

        assertEquals(1, result.size());
        DynamicAttributeSet setA = result.get(0);
        assertEquals(2, setA.getAttributes().size()); // Both attrs present (duplicate name)

        // Both values present
        List<Object> values = setA.getAttributes().stream()
                .map(DynamicAttribute::getValue)
                .toList();
        assertTrue(values.contains("oldValue"));
        assertTrue(values.contains("newValue"));
    }

    // ==================== Edge Cases ====================

    @Test
    void merge_NullExisting() {
        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setA", createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(null, imported, DynamicAttributeMergeStrategy.MERGE);

        assertEquals(1, result.size());
    }

    @Test
    void merge_EmptyImported() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String))
        );

        List<DynamicAttributeSet> result = merger.merge(existing, new HashMap<>(), DynamicAttributeMergeStrategy.MERGE);

        assertEquals(1, result.size());
    }

    @Test
    void merge_NullImported() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String))
        );

        List<DynamicAttributeSet> result = merger.merge(existing, null, DynamicAttributeMergeStrategy.MERGE);

        assertEquals(1, result.size());
    }

    @Test
    void merge_NullStrategy_DefaultsToMerge() {
        List<DynamicAttributeSet> existing = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String))
        );

        Map<String, DynamicAttributeSet> imported = new HashMap<>();
        imported.put("setA", createSet("setA", createAttr("attr2", "value2", DynamicAttributeType.String)));

        List<DynamicAttributeSet> result = merger.merge(existing, imported, null);

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getAttributes().size()); // MERGE behavior
    }

    // ==================== MultiSelect Merge Tests ====================

    @Test
    void mergeMultiSelectValues_BothValues() {
        String result = merger.mergeMultiSelectValues("red,green", "blue,yellow");

        assertTrue(result.contains("red"));
        assertTrue(result.contains("green"));
        assertTrue(result.contains("blue"));
        assertTrue(result.contains("yellow"));
    }

    @Test
    void mergeMultiSelectValues_NoDuplicates() {
        String result = merger.mergeMultiSelectValues("red,green", "green,blue");

        // Should have exactly 3 unique values
        String[] values = result.split(",");
        assertEquals(3, values.length);
    }

    @Test
    void mergeMultiSelectValues_NullExisting() {
        String result = merger.mergeMultiSelectValues(null, "red,green");
        assertEquals("red,green", result);
    }

    @Test
    void mergeMultiSelectValues_NullImported() {
        String result = merger.mergeMultiSelectValues("red,green", null);
        assertEquals("red,green", result);
    }

    // ==================== Find Methods Tests ====================

    @Test
    void findAttribute_Found() {
        DynamicAttributeSet set = createSet("setA",
                createAttr("attr1", "value1", DynamicAttributeType.String),
                createAttr("attr2", "value2", DynamicAttributeType.String));

        Optional<DynamicAttribute> result = merger.findAttribute(set, "attr1");

        assertTrue(result.isPresent());
        assertEquals("value1", result.get().getValue());
    }

    @Test
    void findAttribute_NotFound() {
        DynamicAttributeSet set = createSet("setA",
                createAttr("attr1", "value1", DynamicAttributeType.String));

        Optional<DynamicAttribute> result = merger.findAttribute(set, "attr2");

        assertTrue(result.isEmpty());
    }

    @Test
    void findSet_Found() {
        List<DynamicAttributeSet> sets = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String)),
                createSet("setB", createAttr("attr2", "value2", DynamicAttributeType.String))
        );

        Optional<DynamicAttributeSet> result = merger.findSet(sets, "setB");

        assertTrue(result.isPresent());
        assertEquals("setB", result.get().getName());
    }

    @Test
    void findSet_NotFound() {
        List<DynamicAttributeSet> sets = List.of(
                createSet("setA", createAttr("attr1", "value1", DynamicAttributeType.String))
        );

        Optional<DynamicAttributeSet> result = merger.findSet(sets, "setC");

        assertTrue(result.isEmpty());
    }
}
