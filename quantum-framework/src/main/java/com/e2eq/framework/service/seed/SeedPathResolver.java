package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized resolution of the seed packs root directory.
 * Uses property "quantum.seed-pack.root" and a consistent fallback strategy
 * usable from both startup and admin paths.
 * 
 * Now a CDI bean for proper dependency injection.
 */
@ApplicationScoped
public class SeedPathResolver {
    private static final String DEFAULT_MAIN_SEED_ROOT = "src/main/resources/seed-packs";
    private static final String DEFAULT_TEST_SEED_ROOT = "src/test/resources/seed-packs";

    @ConfigProperty(name = "quantum.seed-pack.root", defaultValue = DEFAULT_MAIN_SEED_ROOT)
    String seedRoot;

    /**
     * Resolves the seed root path using configuration and fallback strategy.
     *
     * @return the resolved seed root path
     */
    public Path resolveSeedRoot() {
        Path primary = Path.of(seedRoot);
        if (Files.exists(primary)) {
            Log.debugf("SeedPathResolver: using configured seed root: %s", primary.toAbsolutePath());
            return primary;
        }
        // Try module-relative fallback when running from monorepo root
        Path fallback = Path.of("quantum-framework").resolve(seedRoot);
        if (Files.exists(fallback)) {
            Log.debugf("SeedPathResolver: using fallback seed root: %s", fallback.toAbsolutePath());
            return fallback;
        }
        // In tests, allow implicit default under src/test/resources/seed-packs
        Path testRoot = Path.of(DEFAULT_TEST_SEED_ROOT);
        if (Files.exists(testRoot)) {
            Log.debugf("SeedPathResolver: using test seed root: %s", testRoot.toAbsolutePath());
            return testRoot;
        }
        // As a last resort, return the primary
        Log.warnf("SeedPathResolver: seed root does not exist, returning configured path: %s", primary.toAbsolutePath());
        return primary;
    }

    /**
     * Static method for backward compatibility.
     * 
     * @deprecated Use CDI injection of SeedPathResolver instead, then call instance method resolveSeedRoot()
     */
    @Deprecated
    public static Path resolveSeedRootStatic() {
        // For backward compatibility, use ConfigProvider directly
        String configured = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("quantum.seed-pack.root", String.class)
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
