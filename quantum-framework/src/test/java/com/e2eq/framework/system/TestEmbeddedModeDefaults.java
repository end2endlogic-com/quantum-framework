package com.e2eq.framework.system;

import com.e2eq.framework.api.system.QuantumMode;
import com.e2eq.framework.system.config.QuantumModeConfig;
import com.e2eq.framework.system.config.SystemRealmOwnership;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The wp3 no-change guarantee, pinned: with no quantum.mode configured the
 * deployment is embedded, owns its system realm, and startup realm lists are
 * untouched.
 */
@QuarkusTest
public class TestEmbeddedModeDefaults {

    @Inject QuantumModeConfig quantumModeConfig;
    @Inject SystemRealmOwnership systemRealmOwnership;
    @Inject EnvConfigUtils envConfigUtils;

    @Test
    public void defaultModeIsEmbedded() {
        Assertions.assertEquals(QuantumMode.EMBEDDED, quantumModeConfig.mode());
        Assertions.assertTrue(quantumModeConfig.isEmbedded());
        Assertions.assertTrue(systemRealmOwnership.ownedLocally());
    }

    @Test
    public void embeddedModeDoesNotTouchStartupRealmLists() {
        String systemRealm = envConfigUtils.getSystemRealm();
        List<String> realms = new ArrayList<>(List.of(systemRealm, "some-app-realm"));

        boolean removed = systemRealmOwnership.excludeSystemRealmIfNotOwned(realms, "TestEmbeddedModeDefaults");

        Assertions.assertFalse(removed);
        Assertions.assertEquals(List.of(systemRealm, "some-app-realm"), realms);
    }
}
