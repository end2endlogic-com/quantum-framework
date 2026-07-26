package com.e2eq.framework.system.tenant;

import com.e2eq.framework.api.tenant.RealmDatabaseLifecycleProvider;
import com.mongodb.client.MongoClient;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.stream.StreamSupport;

/** Default MongoDB dedicated-realm drop implementation with absence verification. */
@ApplicationScoped
@DefaultBean
public class MongoRealmDatabaseLifecycleProvider
    implements RealmDatabaseLifecycleProvider {

    @Inject
    MongoClient mongoClient;

    @Override
    public DropResult dropAndVerify(DropRequest request) {
        mongoClient.getDatabase(request.databaseName()).drop();
        boolean absent = !databaseExists(request.databaseName());
        return new DropResult(absent, absent);
    }

    private boolean databaseExists(String databaseName) {
        return StreamSupport.stream(
                mongoClient.listDatabaseNames().spliterator(), false)
            .anyMatch(databaseName::equals);
    }
}
