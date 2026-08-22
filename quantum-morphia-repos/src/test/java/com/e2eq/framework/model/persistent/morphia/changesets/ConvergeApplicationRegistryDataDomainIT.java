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

class ConvergeApplicationRegistryDataDomainIT {
    @Test
    void convergesLegacyRegistryRowsAndIsIdempotent() {
        String databaseName = "test-application-domain-" + UUID.randomUUID().toString().replace("-", "");
        try (MongoClient mongoClient = MongoClients.create("mongodb://localhost:27017")) {
            MongoDatabase database = mongoClient.getDatabase(databaseName);
            database.getCollection("application").insertMany(List.of(
                application("quantum-system", "quantum-auth"),
                application("helixor-code", "helixor-code-P1")));

            ConvergeApplicationRegistryDataDomain changeSet = new ConvergeApplicationRegistryDataDomain();
            changeSet.systemRealm = databaseName;
            changeSet.systemTenantId = "system.com";
            changeSet.systemOrgRefName = "system.com";
            changeSet.systemAccountNumber = "0000000000";
            changeSet.execute(session(database), mongoClient, emitter());
            changeSet.execute(session(database), mongoClient, emitter());

            for (Document row : database.getCollection("application").find()) {
                Document domain = row.get("dataDomain", Document.class);
                assertEquals("system.com", domain.getString("tenantId"));
                assertEquals("system.com", domain.getString("orgRefName"));
                assertEquals("0000000000", domain.getString("accountNum"));
                assertEquals(0, domain.getInteger("dataSegment"));
            }
            assertEquals("1.0.7", changeSet.getDbFromVersion());
            assertEquals("1.0.8", changeSet.getDbToVersion());
        } finally {
            try (MongoClient cleanup = MongoClients.create("mongodb://localhost:27017")) {
                cleanup.getDatabase(databaseName).drop();
            }
        }
    }

    private static Document application(String refName, String tenantId) {
        return new Document("refName", refName).append("dataDomain",
            new Document("tenantId", tenantId).append("orgRefName", tenantId)
                .append("accountNum", "legacy").append("dataSegment", 7));
    }

    private static MorphiaSession session(MongoDatabase database) {
        return (MorphiaSession) Proxy.newProxyInstance(
            ConvergeApplicationRegistryDataDomainIT.class.getClassLoader(),
            new Class<?>[]{MorphiaSession.class},
            (proxy, method, args) -> "getDatabase".equals(method.getName()) ? database : defaultReturn(method));
    }

    @SuppressWarnings("unchecked")
    private static MultiEmitter<? super String> emitter() {
        return (MultiEmitter<? super String>) Proxy.newProxyInstance(
            ConvergeApplicationRegistryDataDomainIT.class.getClassLoader(),
            new Class<?>[]{MultiEmitter.class},
            (proxy, method, args) -> "emit".equals(method.getName()) ? proxy : defaultReturn(method));
    }

    private static Object defaultReturn(Method method) {
        Class<?> type = method.getReturnType();
        if (type.equals(boolean.class)) return false;
        if (type.equals(int.class)) return 0;
        if (type.equals(long.class)) return 0L;
        return null;
    }
}
