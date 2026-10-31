package com.e2eq.ontology.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.quarkus.logging.Log;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes this module's test databases after its suite: the configured databases, plus
 * those matching a prefix declared in {@code quantum.test.cleanup.owned-database-prefixes}.
 * Other test databases belong to suites or applications sharing the local Mongo and may be
 * in use, whenever they were created, so they are left alone.
 */
public final class TestDatabaseCleanupResource implements QuarkusTestResourceLifecycleManager {

    private static final String TEST_DATABASE_PREFIX = "test-";
    private static final String OWNED_PREFIXES_CONFIG = "quantum.test.cleanup.owned-database-prefixes";
    private MongoClient mongoClient;
    private final Set<String> configuredDatabases = new HashSet<>();
    private List<String> ownedPrefixes = List.of();

    @Override
    public Map<String, String> start() {
        String connectionString = ConfigProvider.getConfig()
                .getValue("quarkus.mongodb.connection-string", String.class);
        mongoClient = MongoClients.create(connectionString);
        for (String key : new String[] {"quarkus.mongodb.database", "quarkus.morphia.database",
                "quantum.realmConfig.systemRealm", "quantum.realmConfig.testRealm",
                "quantum.realmConfig.defaultRealm"}) {
            ConfigProvider.getConfig().getOptionalValue(key, String.class)
                    .ifPresent(configuredDatabases::add);
        }
        ownedPrefixes = ConfigProvider.getConfig()
                .getOptionalValues(OWNED_PREFIXES_CONFIG, String.class)
                .orElse(List.of());
        for (String prefix : ownedPrefixes) {
            // A bare "test-" would claim every suite's databases again.
            if (!prefix.startsWith(TEST_DATABASE_PREFIX) || prefix.equals(TEST_DATABASE_PREFIX)) {
                throw new IllegalArgumentException(OWNED_PREFIXES_CONFIG
                        + " must name this suite's test databases; received: " + prefix);
            }
        }
        return Map.of();
    }

    @Override
    public void stop() {
        if (mongoClient == null) {
            return;
        }
        try {
            for (String databaseName : mongoClient.listDatabaseNames().into(new ArrayList<>())) {
                if (databaseName.startsWith(TEST_DATABASE_PREFIX)
                        && (configuredDatabases.contains(databaseName)
                            || ownedPrefixes.stream().anyMatch(databaseName::startsWith))) {
                    mongoClient.getDatabase(databaseName).drop();
                    Log.infof("Dropped ontology test database during suite teardown: %s", databaseName);
                }
            }
        } finally {
            mongoClient.close();
        }
    }
}
