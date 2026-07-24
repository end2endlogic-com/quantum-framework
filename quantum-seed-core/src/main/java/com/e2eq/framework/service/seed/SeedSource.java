package com.e2eq.framework.service.seed;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Source of seed packs (filesystem, S3, database, etc.).
 */
public interface SeedSource {

    /**
     * Returns a human friendly identifier for this source.
     */
    String getId();

    /**
     * Discover seed packs that are available for the given context.
     */
    List<SeedPackDescriptor> loadSeedPacks(SeedContext context) throws IOException;

    /**
     * Opens a dataset resource for the provided seed pack.
     */
    InputStream openDataset(SeedPackDescriptor descriptor, String relativePath) throws IOException;
}
