package com.e2eq.ontology.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.quarkus.logging.Log;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.Map;

/** Removes every disposable test-prefixed Mongo database after this module's suite. */
public final class TestDatabaseCleanupResource implements QuarkusTestResourceLifecycleManager {

    private static final String TEST_DATABASE_PREFIX = "test-";
    private MongoClient mongoClient;

    @Override
    public Map<String, String> start() {
        String connectionString = ConfigProvider.getConfig()
                .getValue("quarkus.mongodb.connection-string", String.class);
        mongoClient = MongoClients.create(connectionString);
        return Map.of();
    }

    @Override
    public void stop() {
        if (mongoClient == null) {
            return;
        }
        try {
            for (String databaseName : mongoClient.listDatabaseNames().into(new ArrayList<>())) {
                if (databaseName.startsWith(TEST_DATABASE_PREFIX)) {
                    mongoClient.getDatabase(databaseName).drop();
                    Log.infof("Dropped ontology test database during suite teardown: %s", databaseName);
                }
            }
        } finally {
            mongoClient.close();
        }
    }
}
