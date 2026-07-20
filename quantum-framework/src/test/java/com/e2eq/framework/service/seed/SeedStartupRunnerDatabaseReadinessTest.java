package com.e2eq.framework.service.seed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedStartupRunnerDatabaseReadinessTest {

    @Test
    void migrationDisabledAllowsIndependentSeedStartup() {
        SeedStartupRunner runner = new SeedStartupRunner();
        runner.migrationEnabled = false;

        assertFalse(runner.requiresMigrationReadiness());
    }

    @Test
    void migrationEnabledRequiresMigrationReadiness() {
        SeedStartupRunner runner = new SeedStartupRunner();
        runner.migrationEnabled = true;

        assertTrue(runner.requiresMigrationReadiness());
    }
}
