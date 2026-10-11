package com.e2eq.ontology.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.quarkus.logging.Log;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Removes the test-prefixed Mongo databases this module's suite created. Databases that
 * already existed belong to other suites or applications sharing the local Mongo and may
 * be mid-run, so they are left alone unless they are this suite's configured database.
 */
public final class TestDatabaseCleanupResource implements QuarkusTestResourceLifecycleManager {

    private static final String TEST_DATABASE_PREFIX = "test-";
    private MongoClient mongoClient;
    private final Set<String> preexistingDatabases = new HashSet<>();
    private final Set<String> configuredDatabases = new HashSet<>();

    @Override
    public Map<String, String> start() {
        String connectionString = ConfigProvider.getConfig()
                .getValue("quarkus.mongodb.connection-string", String.class);
        mongoClient = MongoClients.create(connectionString);
        mongoClient.listDatabaseNames().into(preexistingDatabases);
        for (String key : new String[] {"quarkus.mongodb.database", "quarkus.morphia.database",
                "quantum.realmConfig.systemRealm", "quantum.realmConfig.testRealm",
                "quantum.realmConfig.defaultRealm"}) {
            ConfigProvider.getConfig().getOptionalValue(key, String.class)
                    .ifPresent(configuredDatabases::add);
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
                            || !preexistingDatabases.contains(databaseName))) {
                    mongoClient.getDatabase(databaseName).drop();
                    Log.infof("Dropped ontology test database during suite teardown: %s", databaseName);
                }
            }
        } finally {
            mongoClient.close();
        }
    }
}
