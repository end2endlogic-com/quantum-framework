package com.e2eq.framework.security;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Test profile that enables the optional RuleIndex in RuleContext via config.
 */
public class IndexEnabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quantum.security.rules.index.enabled", "true"
        );
    }
}
