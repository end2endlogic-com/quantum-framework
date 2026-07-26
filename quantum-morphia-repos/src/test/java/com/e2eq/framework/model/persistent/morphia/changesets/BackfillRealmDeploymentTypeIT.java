package com.e2eq.framework.model.persistent.morphia.changesets;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import dev.morphia.transactions.MorphiaSession;
import io.smallrye.mutiny.subscription.MultiEmitter;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackfillRealmDeploymentTypeIT {

    @Test
    void backfillsOnlyMissingAndNullValuesAndPreservesShared() {
        String databaseName =
            "quantum_realm_type_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (MongoClient mongoClient = MongoClients.create("mongodb://localhost:27017")) {
            MongoDatabase database = mongoClient.getDatabase(databaseName);
            database.getCollection("realm").insertMany(List.of(
                new Document("refName", "legacy-missing"),
                new Document("refName", "legacy-null").append("deploymentType", null),
                new Document("refName", "explicit-shared")
                    .append("deploymentType", "SHARED")));

            BackfillRealmDeploymentType changeSet =
                new BackfillRealmDeploymentType();
            changeSet.systemRealm = databaseName;
            changeSet.execute(session(database), mongoClient, emitter());
            changeSet.execute(session(database), mongoClient, emitter());

            List<Document> realms = database.getCollection("realm")
                .find()
                .into(new java.util.ArrayList<>());
            assertEquals(3, realms.size());
            assertEquals(
                "DEDICATED", deploymentType(realms, "legacy-missing"));
            assertEquals(
                "DEDICATED", deploymentType(realms, "legacy-null"));
            assertEquals(
                "SHARED", deploymentType(realms, "explicit-shared"));
            assertEquals("1.0.5", changeSet.getDbFromVersion());
            assertEquals("1.0.6", changeSet.getDbToVersion());
            assertTrue(changeSet.getApplicableDatabases().contains(databaseName));
        } finally {
            try (MongoClient cleanup = MongoClients.create("mongodb://localhost:27017")) {
                cleanup.getDatabase(databaseName).drop();
            }
        }
    }

    private static String deploymentType(
        List<Document> realms,
        String refName
    ) {
        return realms.stream()
            .filter(document -> refName.equals(document.getString("refName")))
            .findFirst()
            .orElseThrow()
            .getString("deploymentType");
    }

    private static MorphiaSession session(MongoDatabase database) {
        return (MorphiaSession) Proxy.newProxyInstance(
            BackfillRealmDeploymentTypeIT.class.getClassLoader(),
            new Class<?>[]{MorphiaSession.class},
            (proxy, method, args) ->
                "getDatabase".equals(method.getName())
                    ? database
                    : defaultReturn(method));
    }

    @SuppressWarnings("unchecked")
    private static MultiEmitter<? super String> emitter() {
        return (MultiEmitter<? super String>) Proxy.newProxyInstance(
            BackfillRealmDeploymentTypeIT.class.getClassLoader(),
            new Class<?>[]{MultiEmitter.class},
            (proxy, method, args) ->
                "emit".equals(method.getName())
                    ? proxy
                    : defaultReturn(method));
    }

    private static Object defaultReturn(Method method) {
        Class<?> type = method.getReturnType();
        if (type.equals(boolean.class)) {
            return false;
        }
        if (type.equals(int.class)) {
            return 0;
        }
        if (type.equals(long.class)) {
            return 0L;
        }
        return null;
    }
}
