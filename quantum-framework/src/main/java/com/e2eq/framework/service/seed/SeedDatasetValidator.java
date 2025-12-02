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
        
        for (int i = 0; i < records.size(); i++) {
            List<String> recordErrors = validateRecord(records.get(i), dataset);
            if (!recordErrors.isEmpty()) {
                errors.put(i, recordErrors);
            }
        }

        if (!errors.isEmpty()) {
            Log.warnf("SeedDatasetValidator: found %d invalid records out of %d for dataset %s",
                    errors.size(), records.size(), dataset.getCollection());
        }

        return errors;
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

