package com.e2eq.framework.system;

import com.e2eq.framework.api.system.QuantumMode;
import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.system.config.QuantumModeConfig;
import com.e2eq.framework.system.config.SystemRealmOwnership;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase B exit test for the remote half of the mode seam
 * (CONTROL_PLANE_SPLIT_DESIGN.md §9; wp1-platform-readiness.md B3; wp3 tier 2).
 *
 * Boots the framework with {@code quantum.mode=remote}: startup itself proves
 * the system-realm work is skippable (the app must come up without owning the
 * system realm), and the assertions pin the seam's contract — mode resolution,
 * system-realm exclusion, migration realm filtering, and SystemDirectory
 * failing loud until the Phase C control-plane client exists.
 */
@QuarkusTest
@TestProfile(TestRemoteMode.RemoteModeProfile.class)
public class TestRemoteMode {

    public static class RemoteModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quantum.mode", "remote",
                "quantum.system-service.base-url", "http://localhost:9999"
            );
        }
    }

    @Inject QuantumModeConfig quantumModeConfig;
    @Inject SystemRealmOwnership systemRealmOwnership;
    @Inject SystemDirectory systemDirectory;
    @Inject MigrationService migrationService;
    @Inject EnvConfigUtils envConfigUtils;

    @Test
    public void modeIsRemoteWithBaseUrl() {
        Assertions.assertEquals(QuantumMode.REMOTE, quantumModeConfig.mode());
        Assertions.assertTrue(quantumModeConfig.isRemote());
        Assertions.assertEquals("http://localhost:9999",
            quantumModeConfig.systemServiceBaseUrl().orElse(null));
    }

    @Test
    public void systemRealmIsNotOwnedAndGetsExcluded() {
        Assertions.assertFalse(systemRealmOwnership.ownedLocally());

        String systemRealm = envConfigUtils.getSystemRealm();
        List<String> realms = new ArrayList<>(List.of(systemRealm, "some-app-realm"));
        boolean removed = systemRealmOwnership.excludeSystemRealmIfNotOwned(realms, "TestRemoteMode");

        Assertions.assertTrue(removed);
        Assertions.assertEquals(List.of("some-app-realm"), realms);
    }

    @Test
    public void migrationStartupRealmsExcludeSystemRealmWhenNotIncluded() {
        String systemRealm = envConfigUtils.getSystemRealm();
        String defaultRealm = envConfigUtils.getDefaultRealm();

        List<String> withSystem = migrationService.resolveStartupRealms(List.of(defaultRealm), true);
        Assertions.assertTrue(withSystem.contains(systemRealm),
            "embedded behavior: system realm leads the startup realms");

        List<String> withoutSystem = migrationService.resolveStartupRealms(List.of(defaultRealm), false);
        Assertions.assertFalse(withoutSystem.contains(systemRealm),
            "remote behavior: system realm excluded from startup realms");
        Assertions.assertTrue(withoutSystem.contains(defaultRealm),
            "remote behavior: app realms still migrate locally");
    }

    @Test
    public void systemDirectoryFailsLoudUntilPhaseC() {
        RuntimeException failure = Assertions.assertThrows(RuntimeException.class,
            () -> systemDirectory.systemRealmId());

        boolean mentionsPhaseC = false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("Phase C")) {
                mentionsPhaseC = true;
                break;
            }
        }
        Assertions.assertTrue(mentionsPhaseC,
            "remote SystemDirectory must fail loud (mentioning Phase C), got: " + failure);
    }
}
