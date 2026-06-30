package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.util.EnvConfigUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level coverage for the {@code quantum.realm.seed-system-only} guard in
 * {@link SeedStartupRunner#resolveStartupRealms()}.
 *
 * <p>The private {@code resolveStartupRealms()} reads the CSV config, the {@link RealmRepo}
 * flagged realms, and (in the fallback branch) the {@link EnvConfigUtils} realm names. A stub
 * {@link RealmRepo} returns no flagged realms so this runs without Quarkus or Mongo; the private
 * method is invoked via reflection.
 */
class SeedStartupRunnerSeedSystemOnlyRealmsTest {

    @SuppressWarnings("unchecked")
    private static List<String> resolve(SeedStartupRunner runner) throws Exception {
        Method m = SeedStartupRunner.class.getDeclaredMethod("resolveStartupRealms");
        m.setAccessible(true);
        return (List<String>) m.invoke(runner);
    }

    private static EnvConfigUtils envConfig() {
        EnvConfigUtils env = new EnvConfigUtils();
        env.setSystemRealm("system-com");
        env.setDefaultRealm("mycompanyxyz-com");
        env.setTestRealm("test-system-com");
        return env;
    }

    private static SeedStartupRunner newRunner(boolean seedSystemOnly, Optional<String> applyRealmsCsv) {
        SeedStartupRunner runner = new SeedStartupRunner();
        runner.envConfigUtils = envConfig();
        runner.realmRepo = new NoFlaggedRealmsRepo();
        runner.startupRealmsCsv = applyRealmsCsv;
        runner.seedSystemOnly = seedSystemOnly;
        return runner;
    }

    @Test
    void defaultFallbackResolvesSystemDefaultAndTestRealms() throws Exception {
        List<String> realms = resolve(newRunner(false, Optional.empty()));

        assertTrue(realms.contains("system-com"), "system realm must be resolved in default mode");
        assertTrue(realms.contains("mycompanyxyz-com"), "default realm must be resolved in default mode");
        assertTrue(realms.contains("test-system-com"), "test realm must be resolved in default mode");
        assertEquals(3, realms.size(), "default fallback resolves system + default + test realms");
    }

    @Test
    void seedSystemOnlyResolvesOnlySystemRealm() throws Exception {
        List<String> realms = resolve(newRunner(true, Optional.empty()));

        assertTrue(realms.contains("system-com"), "system realm must still be resolved under seed-system-only");
        assertFalse(realms.contains("mycompanyxyz-com"),
                "default realm must NOT be resolved under seed-system-only");
        assertFalse(realms.contains("test-system-com"),
                "test realm must NOT be resolved under seed-system-only");
        assertEquals(1, realms.size(), "seed-system-only resolves exactly the system realm");
    }

    @Test
    void seedSystemOnlyStillHonorsExplicitApplyRealms() throws Exception {
        List<String> realms = resolve(newRunner(true, Optional.of("mycompanyxyz-com")));

        assertTrue(realms.contains("mycompanyxyz-com"),
                "an explicit quantum.seed-pack.apply.realms entry must still be resolved under seed-system-only");
        assertFalse(realms.contains("test-system-com"),
                "test realm must NOT be resolved (it was not explicitly opted in)");
        assertEquals(1, realms.size(), "only the explicitly opted-in realm is resolved (no fallback when CSV set)");
    }

    /** Stub RealmRepo returning no applySeedsOnStartup-flagged realms; keeps the test off Mongo. */
    private static final class NoFlaggedRealmsRepo extends RealmRepo {
        @Override
        public List<Realm> findRealmsWithSeedsEnabled() {
            return Collections.emptyList();
        }
    }
}
