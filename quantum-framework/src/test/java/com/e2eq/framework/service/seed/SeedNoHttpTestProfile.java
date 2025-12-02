package com.e2eq.framework.service.seed;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * Test profile to avoid HTTP port conflicts by letting Quarkus choose random ports
 * and disabling optional HTTP features not needed for these tests.
 */
public class SeedNoHttpTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> cfg = new HashMap<>();
        // Let Quarkus pick random available ports for HTTP/HTTPS during tests
        cfg.put("quarkus.http.port", "0");
        cfg.put("quarkus.http.test-port", "0");
        cfg.put("quarkus.http.ssl-port", "0");
        // Do not start the HTTP server at all in tests that don't need it
        cfg.put("quarkus.test.http-server.enable", "false");
        // Bind explicitly to loopback if anything starts
        cfg.put("quarkus.http.host", "127.0.0.1");
        // Use a dedicated Morphia database for tests to avoid collisions with default/system DB
        cfg.put("quarkus.morphia.database", "seed-tests");
        cfg.put("quarkus.mongodb.database", "seed-tests");
        // Avoid Morphia auto index creation to reduce startup work and flakiness
        cfg.put("quarkus.morphia.create-indexes", "false");
        // Disable database migrations during these tests; tests drive seeding explicitly
        cfg.put("quantum.database.migration.enabled", "false");
        // Reduce noise and avoid starting dev services we don't need
        cfg.put("quarkus.devservices.enabled", "false");
        // Ensure seed discovery reads from test resources
        cfg.put("quantum.seed.root", "src/test/resources/seed-packs");
        // Keep seed apply on startup disabled for these tests to avoid interference
        cfg.put("quantum.seed-pack.apply.on-startup", "false");
        return cfg;
    }
}
