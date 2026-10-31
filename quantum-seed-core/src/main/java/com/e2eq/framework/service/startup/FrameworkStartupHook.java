package com.e2eq.framework.service.startup;

/**
 * Extension point for startup work that only some deployment slices carry.
 *
 * <p>{@link FrameworkStartupCoordinator} lives in {@code quantum-seed-core} so that every
 * slice that persists data — a tenant plane as much as the full {@code quantum-framework}
 * aggregate — runs realm migrations and seed packs from configuration alone. Work that
 * needs modules a tenant plane does not carry (baseline identity, bootstrap packs) is
 * contributed by implementing this interface; the aggregate registers one. Hooks are
 * discovered through CDI and run in ascending {@link #order()}.</p>
 */
public interface FrameworkStartupHook {

    /** Ordering among hooks; lower runs first. */
    default int order() {
        return 0;
    }

    /**
     * Runs after realm migrations. {@code embedded} is true when this process owns its
     * system realm ({@code quantum.mode=embedded}).
     */
    default void afterMigration(boolean embedded) {
    }

    /** Runs after seed packs have been applied. */
    default void afterSeeds(boolean embedded) {
    }

    /** Runs after the pending-seeds startup report; the last step of the sequence. */
    default void afterStartupReport(boolean embedded) {
    }
}
