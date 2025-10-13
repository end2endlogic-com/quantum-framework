package com.e2eq.framework.service.seed;

import java.util.Map;

import com.e2eq.framework.service.seed.SeedPackManifest.Dataset;
import com.e2eq.framework.service.seed.SeedPackManifest.Index;

/**
 * Persists records from datasets into the tenant data store.
 */
public interface SeedRepository {

    void ensureIndexes(SeedContext context, Dataset dataset, Index index);

    void upsertRecord(SeedContext context, Dataset dataset, Map<String, Object> record);
}
