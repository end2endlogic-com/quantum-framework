package com.e2eq.framework.service.seed;

import java.util.Optional;

/**
 * Tracks which seed datasets have been applied to a tenant.
 */
public interface SeedRegistry {

    /** Read-only availability probe; implementations must verify their backing store. */
    default void checkReadiness(SeedContext context) {
        throw new IllegalStateException("SEED_REGISTRY_READINESS_UNSUPPORTED");
    }

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
            @Override public void checkReadiness(SeedContext context) { /* Explicit no-op registry has no backing store. */ }
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
