package com.e2eq.framework.service.seed;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * Test profile for tests that validate seed application on startup.
 * - Uses random HTTP ports to avoid conflicts.
 * - Enables seed application on startup.
 */
public class SeedStartupTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("quarkus.http.test-port", "0");
        cfg.put("quarkus.http.ssl-port", "0");
        cfg.put("quarkus.devservices.enabled", "false");
        cfg.put("quantum.seed.root", "src/test/resources/seed-packs");
        // Explicitly enable startup seeds for this test profile
        cfg.put("quantum.seed-pack.apply.on-startup", "true");
        return cfg;
    }
}
