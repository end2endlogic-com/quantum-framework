package com.e2eq.framework.service.seed;

import java.util.Optional;

/**
 * Tracks which seed datasets have been applied to a tenant.
 */
public interface SeedRegistry {

    boolean shouldApply(SeedContext context,
                        SeedPackManifest manifest,
                        SeedPackManifest.Dataset dataset,
                        String checksum);

    void recordApplied(SeedContext context,
                       SeedPackManifest manifest,
                       SeedPackManifest.Dataset dataset,
                       String checksum,
                       int recordsApplied);

    /**
     * Returns the last recorded checksum for the given dataset in this realm, if any.
     * Default implementation returns empty for registries that don't track it.
     */
    default Optional<String> getLastAppliedChecksum(SeedContext context,
                                                    SeedPackManifest manifest,
                                                    SeedPackManifest.Dataset dataset) {
        return Optional.empty();
    }

    static SeedRegistry noop() {
        return new SeedRegistry() {
            @Override
            public boolean shouldApply(SeedContext context, SeedPackManifest manifest, SeedPackManifest.Dataset dataset, String checksum) {
                return true;
            }

            @Override
            public void recordApplied(SeedContext context, SeedPackManifest manifest, SeedPackManifest.Dataset dataset, String checksum, int recordsApplied) {
                // no-op
            }
        };
    }
}
