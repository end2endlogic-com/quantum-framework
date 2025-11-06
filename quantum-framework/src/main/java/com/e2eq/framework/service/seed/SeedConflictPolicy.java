package com.e2eq.framework.service.seed;

/**
 * Policy for handling conflicts when applying seed datasets that have already been applied before
 * and whose current checksum differs from the previously recorded checksum in the registry.
 *
 * Note: Current implementation evaluates conflicts at the dataset level (checksum of the dataset file),
 * not per-record. This keeps changes minimal while allowing callers to choose a conservative policy.
 */
public enum SeedConflictPolicy {
    /** Always apply the seed dataset even if the previous checksum differs (preserves legacy behavior). */
    SEED_WINS,

    /** Skip applying the dataset if the previous checksum differs; log a warning that existing data wins. */
    EXISTING_WINS,

    /** Fail with an error if the previous checksum differs, requiring an explicit policy change to proceed. */
    ERROR
}
