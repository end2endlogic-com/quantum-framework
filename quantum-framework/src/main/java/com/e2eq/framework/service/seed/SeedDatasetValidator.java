package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates dataset records before persistence.
 * Ensures required fields are present and values are valid.
 */
@ApplicationScoped
public class SeedDatasetValidator {

    /**
     * Validates a dataset record against the dataset definition.
     *
     * @param record the record to validate
     * @param dataset the dataset definition
     * @return list of validation errors (empty if valid)
     */
    public List<String> validateRecord(Map<String, Object> record, SeedPackManifest.Dataset dataset) {
        List<String> errors = new ArrayList<>();

        if (record == null || record.isEmpty()) {
            errors.add("Record is null or empty");
            return errors;
        }

        // Validate natural key fields are present
        List<String> naturalKey = dataset.getNaturalKey();
        if (naturalKey != null && !naturalKey.isEmpty()) {
            for (String key : naturalKey) {
                if (!record.containsKey(key) || record.get(key) == null) {
                    errors.add(String.format("Natural key field '%s' is missing or null", key));
                }
            }
        }

        // Validate required fields (if dataset defines them)
        // This could be extended to support a 'requiredFields' property in the manifest

        return errors;
    }

    /**
     * Validates a batch of records.
     *
     * @param records the records to validate
     * @param dataset the dataset definition
     * @return map of record index to validation errors (empty map if all valid)
     */
    public Map<Integer, List<String>> validateBatch(List<Map<String, Object>> records, SeedPackManifest.Dataset dataset) {
        Map<Integer, List<String>> errors = new java.util.HashMap<>();

        // 1) Per-record validations (required fields etc.)
        for (int i = 0; i < records.size(); i++) {
            List<String> recordErrors = validateRecord(records.get(i), dataset);
            if (!recordErrors.isEmpty()) {
                errors.put(i, recordErrors);
            }
        }

        // 2) Duplicate detection within this dataset payload (pre-transform)
        detectDuplicates(records, dataset, errors);

        if (!errors.isEmpty()) {
            Log.warnf("SeedDatasetValidator: found %d invalid records out of %d for dataset %s",
                    errors.size(), records.size(), dataset.getCollection());
        }

        return errors;
    }

    /**
     * Detects duplicate identifiers within the provided records and appends errors per offending index.
     *
     * Rules checked (when fields are present):
     * - Duplicate id ("_id" or "id") values
     * - Duplicate refName values
     * - Duplicate natural key tuples (based on manifest.naturalKey)
     */
    public void detectDuplicates(List<Map<String, Object>> records,
                                 SeedPackManifest.Dataset dataset,
                                 Map<Integer, List<String>> errors) {
        Map<String, Integer> seenIds = new java.util.HashMap<>();
        Map<String, Integer> seenRefNames = new java.util.HashMap<>();
        // Some datasets (e.g., code lists) may omit explicit refName but include 'category' + 'key'.
        // Track duplicates for the derived reference "category:key" as well.
        Map<String, Integer> seenDerivedRefNames = new java.util.HashMap<>();
        Map<String, Integer> seenNaturalKeys = new java.util.HashMap<>();

        List<String> naturalKey = dataset.getNaturalKey();

        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> r = records.get(i);

            // id or _id
            Object rawId = (r.containsKey("_id") ? r.get("_id") : r.get("id"));
            if (rawId != null) {
                String idVal = String.valueOf(rawId);
                Integer first = seenIds.putIfAbsent(idVal, i);
                if (first != null) {
                    addError(errors, i, String.format("Duplicate id value '%s' (first seen at record %d)", idVal, first));
                }
            }

            // refName (or derive from category+key if absent)
            Object rawRef = r.get("refName");
            if (rawRef != null) {
                String refVal = String.valueOf(rawRef);
                Integer first = seenRefNames.putIfAbsent(refVal, i);
                if (first != null) {
                    addError(errors, i, String.format("Duplicate refName '%s' (first seen at record %d)", refVal, first));
                }
            } else {
                Object category = r.get("category");
                Object key = r.get("key");
                if (category != null && key != null) {
                    String derived = String.valueOf(category) + ":" + String.valueOf(key);
                    Integer first = seenDerivedRefNames.putIfAbsent(derived, i);
                    if (first != null) {
                        addError(errors, i, String.format(
                                "Duplicate refName (derived from category+key) '%s' (first seen at record %d)",
                                derived, first));
                    }
                }
            }

            // natural key tuple
            if (naturalKey != null && !naturalKey.isEmpty() && hasNaturalKey(r, naturalKey)) {
                String nk = buildNaturalKey(r, naturalKey);
                Integer first = seenNaturalKeys.putIfAbsent(nk, i);
                if (first != null) {
                    addError(errors, i, String.format("Duplicate naturalKey %s (first seen at record %d)", nk, first));
                }
            }
        }
    }

    private static void addError(Map<Integer, List<String>> errors, int index, String message) {
        errors.computeIfAbsent(index, k -> new ArrayList<>()).add(message);
    }

    private static String buildNaturalKey(Map<String, Object> record, List<String> naturalKey) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < naturalKey.size(); i++) {
            String key = naturalKey.get(i);
            Object val = record.get(key);
            if (i > 0) sb.append('|');
            sb.append(key).append('=').append(String.valueOf(val));
        }
        return sb.toString();
    }

    /**
     * Checks if a record has all required natural key fields.
     *
     * @param record the record to check
     * @param naturalKey the list of natural key field names
     * @return true if all natural key fields are present and non-null
     */
    public boolean hasNaturalKey(Map<String, Object> record, List<String> naturalKey) {
        if (naturalKey == null || naturalKey.isEmpty()) {
            return true; // No natural key required
        }
        return naturalKey.stream()
                .allMatch(key -> record.containsKey(key) && record.get(key) != null);
    }
}
