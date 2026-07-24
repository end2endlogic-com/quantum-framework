package com.e2eq.framework.imports.dynamic;

import com.e2eq.framework.model.persistent.base.DynamicAttribute;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import com.e2eq.framework.model.persistent.imports.DynamicAttributeMergeStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for merging dynamic attributes during import.
 * Supports different merge strategies: REPLACE, MERGE, APPEND.
 */
@ApplicationScoped
public class DynamicAttributeMerger {

    private static final Logger LOG = Logger.getLogger(DynamicAttributeMerger.class);

    /**
     * Merge imported attribute sets with existing ones.
     *
     * @param existing the existing attribute sets (may be null)
     * @param imported the imported attribute sets
     * @param strategy the merge strategy
     * @return merged attribute sets
     */
    public List<DynamicAttributeSet> merge(List<DynamicAttributeSet> existing,
                                            Map<String, DynamicAttributeSet> imported,
                                            DynamicAttributeMergeStrategy strategy) {
        if (imported == null || imported.isEmpty()) {
            return existing != null ? existing : new ArrayList<>();
        }

        if (strategy == null) {
            strategy = DynamicAttributeMergeStrategy.MERGE;
        }

        return switch (strategy) {
            case REPLACE -> mergeReplace(existing, imported);
            case MERGE -> mergeMerge(existing, imported);
            case APPEND -> mergeAppend(existing, imported);
        };
    }

    /**
     * REPLACE strategy: Imported sets completely replace existing sets with the same name.
     * Existing sets not in import are preserved.
     */
    private List<DynamicAttributeSet> mergeReplace(List<DynamicAttributeSet> existing,
                                                    Map<String, DynamicAttributeSet> imported) {
        Map<String, DynamicAttributeSet> result = new LinkedHashMap<>();

        // Add existing sets first
        if (existing != null) {
            for (DynamicAttributeSet set : existing) {
                result.put(set.getName(), set);
            }
        }

        // Replace with imported sets
        for (Map.Entry<String, DynamicAttributeSet> entry : imported.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
            LOG.debugf("REPLACE: Set '%s' replaced with imported version", entry.getKey());
        }

        return new ArrayList<>(result.values());
    }

    /**
     * MERGE strategy: Imported attributes are merged into existing sets.
     * For matching attribute names, imported values overwrite existing.
     */
    private List<DynamicAttributeSet> mergeMerge(List<DynamicAttributeSet> existing,
                                                  Map<String, DynamicAttributeSet> imported) {
        Map<String, DynamicAttributeSet> result = new LinkedHashMap<>();

        // Index existing sets
        if (existing != null) {
            for (DynamicAttributeSet set : existing) {
                result.put(set.getName(), cloneSet(set));
            }
        }

        // Merge imported sets
        for (Map.Entry<String, DynamicAttributeSet> entry : imported.entrySet()) {
            String setName = entry.getKey();
            DynamicAttributeSet importedSet = entry.getValue();

            DynamicAttributeSet existingSet = result.get(setName);
            if (existingSet == null) {
                // New set, add directly
                result.put(setName, importedSet);
                LOG.debugf("MERGE: New set '%s' added", setName);
            } else {
                // Merge attributes
                mergeAttributes(existingSet, importedSet.getAttributes());
                LOG.debugf("MERGE: Set '%s' merged with %d imported attributes",
                        setName, importedSet.getAttributes() != null ?
                                importedSet.getAttributes().size() : 0);
            }
        }

        return new ArrayList<>(result.values());
    }

    /**
     * APPEND strategy: Imported attributes are appended to existing sets.
     * Existing attributes with same name are NOT overwritten (duplicates may exist).
     */
    private List<DynamicAttributeSet> mergeAppend(List<DynamicAttributeSet> existing,
                                                   Map<String, DynamicAttributeSet> imported) {
        Map<String, DynamicAttributeSet> result = new LinkedHashMap<>();

        // Index existing sets
        if (existing != null) {
            for (DynamicAttributeSet set : existing) {
                result.put(set.getName(), cloneSet(set));
            }
        }

        // Append imported sets
        for (Map.Entry<String, DynamicAttributeSet> entry : imported.entrySet()) {
            String setName = entry.getKey();
            DynamicAttributeSet importedSet = entry.getValue();

            DynamicAttributeSet existingSet = result.get(setName);
            if (existingSet == null) {
                // New set, add directly
                result.put(setName, importedSet);
                LOG.debugf("APPEND: New set '%s' added", setName);
            } else {
                // Append attributes (allow duplicates)
                appendAttributes(existingSet, importedSet.getAttributes());
                LOG.debugf("APPEND: Set '%s' appended with %d imported attributes",
                        setName, importedSet.getAttributes() != null ?
                                importedSet.getAttributes().size() : 0);
            }
        }

        return new ArrayList<>(result.values());
    }

    /**
     * Merge attributes into an existing set (overwrites matching names).
     */
    private void mergeAttributes(DynamicAttributeSet target, List<DynamicAttribute> imported) {
        if (imported == null || imported.isEmpty()) {
            return;
        }

        if (target.getAttributes() == null) {
            target.setAttributes(new ArrayList<>());
        }

        // Index existing attributes by name
        Map<String, Integer> existingIndex = new HashMap<>();
        List<DynamicAttribute> existingList = target.getAttributes();
        for (int i = 0; i < existingList.size(); i++) {
            existingIndex.put(existingList.get(i).getName(), i);
        }

        // Merge imported attributes
        for (DynamicAttribute importedAttr : imported) {
            Integer existingIdx = existingIndex.get(importedAttr.getName());
            if (existingIdx != null) {
                // Replace existing
                existingList.set(existingIdx, importedAttr);
                LOG.tracef("MERGE: Attribute '%s' replaced", importedAttr.getName());
            } else {
                // Add new
                existingList.add(importedAttr);
                existingIndex.put(importedAttr.getName(), existingList.size() - 1);
                LOG.tracef("MERGE: Attribute '%s' added", importedAttr.getName());
            }
        }
    }

    /**
     * Append attributes to an existing set (allows duplicates).
     */
    private void appendAttributes(DynamicAttributeSet target, List<DynamicAttribute> imported) {
        if (imported == null || imported.isEmpty()) {
            return;
        }

        if (target.getAttributes() == null) {
            target.setAttributes(new ArrayList<>());
        }

        target.getAttributes().addAll(imported);
    }

    /**
     * Clone a DynamicAttributeSet (shallow clone of set, deep clone of attribute list).
     */
    private DynamicAttributeSet cloneSet(DynamicAttributeSet original) {
        DynamicAttributeSet clone = new DynamicAttributeSet();
        clone.setName(original.getName());

        if (original.getAttributes() != null) {
            clone.setAttributes(new ArrayList<>(original.getAttributes()));
        }

        return clone;
    }

    /**
     * Merge two maps of attribute sets.
     *
     * @param first first map
     * @param second second map (takes precedence on conflict)
     * @param strategy the merge strategy
     * @return merged map
     */
    public Map<String, DynamicAttributeSet> mergeMaps(Map<String, DynamicAttributeSet> first,
                                                       Map<String, DynamicAttributeSet> second,
                                                       DynamicAttributeMergeStrategy strategy) {
        if (first == null || first.isEmpty()) {
            return second != null ? second : new HashMap<>();
        }
        if (second == null || second.isEmpty()) {
            return first;
        }

        // Convert first to list
        List<DynamicAttributeSet> firstList = new ArrayList<>(first.values());

        // Merge with second
        List<DynamicAttributeSet> merged = merge(firstList, second, strategy);

        // Convert back to map
        return merged.stream()
                .collect(Collectors.toMap(
                        DynamicAttributeSet::getName,
                        set -> set,
                        (a, b) -> b,  // In case of duplicate names, use second
                        LinkedHashMap::new
                ));
    }

    /**
     * Merge MultiSelect values for APPEND strategy.
     * Combines comma-separated values without duplicates.
     */
    public String mergeMultiSelectValues(String existing, String imported) {
        if (existing == null || existing.isEmpty()) {
            return imported;
        }
        if (imported == null || imported.isEmpty()) {
            return existing;
        }

        Set<String> values = new LinkedHashSet<>();

        // Add existing values
        for (String val : existing.split(",")) {
            values.add(val.trim());
        }

        // Add imported values
        for (String val : imported.split(",")) {
            values.add(val.trim());
        }

        return String.join(",", values);
    }

    /**
     * Find attribute by name in a set.
     */
    public Optional<DynamicAttribute> findAttribute(DynamicAttributeSet set, String attrName) {
        if (set == null || set.getAttributes() == null || attrName == null) {
            return Optional.empty();
        }

        return set.getAttributes().stream()
                .filter(attr -> attrName.equals(attr.getName()))
                .findFirst();
    }

    /**
     * Find attribute set by name in a list.
     */
    public Optional<DynamicAttributeSet> findSet(List<DynamicAttributeSet> sets, String setName) {
        if (sets == null || setName == null) {
            return Optional.empty();
        }

        return sets.stream()
                .filter(set -> setName.equals(set.getName()))
                .findFirst();
    }
}
