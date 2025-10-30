package com.e2eq.framework.service.seed;

import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized resolution of the seed packs root directory.
 * Uses property "quantum.seed-pack.root" and a consistent fallback strategy
 * usable from both startup and admin paths.
 */
public final class SeedPathResolver {
    private static final String PROP = "quantum.seed-pack.root";
    private static final String DEFAULT_MAIN_SEED_ROOT = "src/main/resources/seed-packs";
    private static final String DEFAULT_TEST_SEED_ROOT = "src/test/resources/seed-packs";

    private SeedPathResolver() {}

    public static Path resolveSeedRoot() {
        String configured = ConfigProvider.getConfig()
                .getOptionalValue(PROP, String.class)
                .orElse(DEFAULT_MAIN_SEED_ROOT);
        Path primary = Path.of(configured);
        if (Files.exists(primary)) return primary;
        // Try module-relative fallback when running from monorepo root
        Path fallback = Path.of("quantum-framework").resolve(configured);
        if (Files.exists(fallback)) return fallback;
        // In tests, allow implicit default under src/test/resources/seed-packs
        Path testRoot = Path.of(DEFAULT_TEST_SEED_ROOT);
        if (Files.exists(testRoot)) return testRoot;
        // As a last resort, return the primary
        return primary;
    }
}
