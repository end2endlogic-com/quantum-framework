package com.e2eq.framework.service.seed;

import java.util.List;
import java.util.Map;

import com.e2eq.framework.service.seed.SeedPackManifest.Dataset;
import com.e2eq.framework.service.seed.SeedPackManifest.Index;

/**
 * Persists records from datasets into the tenant data store.
 */
public interface SeedRepository {

    void ensureIndexes(SeedContext context, Dataset dataset, Index index);

    void upsertRecord(SeedContext context, Dataset dataset, Map<String, Object> record);

    /**
     * Optional batch upsert operation. Default implementation falls back to calling {@link #upsertRecord}
     * for each record. Implementations may override to use datastore-specific bulk operations.
     */
    default void upsertBatch(SeedContext context, Dataset dataset, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;
        for (Map<String, Object> rec : records) {
            upsertRecord(context, dataset, rec);
        }
    }

    /**
     * Optional batch insert-only operation. Default implementation falls back to calling {@link #upsertRecord}
     * for each record.
     */
    default void insertBatch(SeedContext context, Dataset dataset, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;
        for (Map<String, Object> rec : records) {
            upsertRecord(context, dataset, rec);
        }
    }
}
