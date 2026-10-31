package com.e2eq.framework.model.persistent.migration.base;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level coverage for the {@code quantum.realm.seed-system-only} guard in
 * {@link MigrationService#resolveStartupRealms(List, boolean)}.
 *
 * <p>{@code resolveStartupRealms} only reads the package-private/protected config fields
 * (systemRealm/defaultRealm/testRealm/startupRealmsCsv/seedSystemOnly) and the supplied list of
 * existing database names, so it runs without Quarkus or Mongo when those fields are set directly.
 */
class MigrationServiceSeedSystemOnlyRealmsTest {

    private static MigrationService newService(boolean seedSystemOnly, Optional<String> applyRealmsCsv) {
        MigrationService svc = new MigrationService();
        svc.systemRealm = "system-com";
        svc.defaultRealm = "mycompanyxyz-com";
        svc.testRealm = "test-system-com";
        svc.startupRealmsCsv = applyRealmsCsv;
        svc.seedSystemOnly = seedSystemOnly;
        return svc;
    }

    @Test
    void defaultIncludesSystemAndDefaultRealms() {
        MigrationService svc = newService(false, Optional.empty());

        List<String> realms = svc.resolveStartupRealms(
                List.of("system-com", "mycompanyxyz-com"), true);

        assertTrue(realms.contains("system-com"), "system realm must be resolved in default mode");
        assertTrue(realms.contains("mycompanyxyz-com"),
                "existing default realm must be resolved in default mode");
        assertEquals(2, realms.size(), "default mode resolves exactly system + existing default realm");
    }

    @Test
    void seedSystemOnlyResolvesOnlySystemRealm() {
        MigrationService svc = newService(true, Optional.empty());

        // Both default and test realm databases exist, but neither should be scanned.
        List<String> realms = svc.resolveStartupRealms(
                List.of("system-com", "mycompanyxyz-com", "test-system-com"), true);

        assertTrue(realms.contains("system-com"), "system realm must still be resolved under seed-system-only");
        assertFalse(realms.contains("mycompanyxyz-com"),
                "default realm must NOT be resolved under seed-system-only");
        assertFalse(realms.contains("test-system-com"),
                "test realm must NOT be resolved under seed-system-only");
        assertEquals(1, realms.size(), "seed-system-only resolves exactly the system realm");
    }

    @Test
    void seedSystemOnlyStillHonorsExplicitApplyRealms() {
        MigrationService svc = newService(true, Optional.of("mycompanyxyz-com"));

        List<String> realms = svc.resolveStartupRealms(
                List.of("system-com", "mycompanyxyz-com", "test-system-com"), true);

        assertTrue(realms.contains("system-com"), "system realm must be resolved under seed-system-only");
        assertTrue(realms.contains("mycompanyxyz-com"),
                "an explicit quantum.migration.apply.realms entry must still be resolved under seed-system-only");
        assertFalse(realms.contains("test-system-com"),
                "test realm must NOT be resolved (it was not explicitly opted in)");
        assertEquals(2, realms.size(), "system + explicitly opted-in realm");
    }
}
