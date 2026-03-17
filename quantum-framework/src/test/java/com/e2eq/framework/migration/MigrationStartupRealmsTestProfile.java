package com.e2eq.framework.migration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

public class MigrationStartupRealmsTestProfile implements QuarkusTestProfile {

    public static final String EXTRA_REALM = "startup-extra-apply-realms-it-com";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("quarkus.http.test-port", "0");
        cfg.put("quarkus.http.ssl-port", "0");
        cfg.put("quarkus.devservices.enabled", "false");
        cfg.put("quantum.migration.apply.realms", EXTRA_REALM);
        return cfg;
    }
}
