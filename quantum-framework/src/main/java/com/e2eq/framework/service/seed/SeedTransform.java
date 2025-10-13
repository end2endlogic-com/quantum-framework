package com.e2eq.framework.service.seed;

import java.util.Map;

import com.e2eq.framework.service.seed.SeedPackManifest.Dataset;

/**
 * Mutates or enriches a record from a seed dataset before it is persisted.
 */
@FunctionalInterface
public interface SeedTransform {

    /**
     * Applies the transform to the provided record.
     *
     * @param record  the current record (never null)
     * @param context tenant context
     * @param dataset dataset metadata
     * @return the new record state; returning {@code null} skips persistence
     */
    Map<String, Object> apply(Map<String, Object> record, SeedContext context, Dataset dataset);
}
