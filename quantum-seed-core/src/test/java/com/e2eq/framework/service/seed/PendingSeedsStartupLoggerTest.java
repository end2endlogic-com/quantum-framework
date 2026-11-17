package com.e2eq.framework.service.seed;

import com.e2eq.framework.util.EnvConfigUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PendingSeedsStartupLoggerTest {

    /** Counts how often the report asks for a realm; every switch-off must skip that entirely. */
    static class CountingEnv extends EnvConfigUtils {
        int asked;
        @Override public String getSystemRealm() { asked++; return "system-realm"; }
        @Override public String getDefaultRealm() { asked++; return "system-realm"; }
        @Override public String getTestRealm() { asked++; return "system-realm"; }
    }

    private static int realmLookups(boolean seedsEnabled, boolean reportOnStartup) {
        PendingSeedsStartupLogger report = new PendingSeedsStartupLogger();
        CountingEnv env = new CountingEnv();
        report.envConfigUtils = env;
        report.seedsEnabled = seedsEnabled;
        report.reportOnStartup = reportOnStartup;
        try {
            report.onStart();
        } catch (RuntimeException expected) {
            // With the report on, the missing discovery/registry beans fail after the realm lookups.
        }
        return env.asked;
    }

    @Test
    void reportIsSkippedWhenTurnedOff() {
        assertEquals(0, realmLookups(true, false));
    }

    @Test
    void reportIsSkippedWhenSeedingIsOff() {
        assertEquals(0, realmLookups(false, true));
    }

    @Test
    void reportRunsByDefault() {
        assertEquals(true, realmLookups(true, true) > 0);
    }
}
