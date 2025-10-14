package com.e2eq.framework.service.seed;

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
