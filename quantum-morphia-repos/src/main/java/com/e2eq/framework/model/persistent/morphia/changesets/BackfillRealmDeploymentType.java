package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.mongodb.client.MongoClient;
import com.mongodb.client.result.UpdateResult;
import dev.morphia.transactions.MorphiaSession;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.runtime.Startup;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Set;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Updates.set;

/**
 * Makes the legacy realm topology explicit in the system realm catalog.
 *
 * <p>The filter deliberately matches only missing or null values. Realms that
 * an operator has already classified as SHARED are never overwritten.</p>
 */
@Startup
@ApplicationScoped
public class BackfillRealmDeploymentType extends ChangeSetBase {

    static final String REALM_COLLECTION = "realm";
    static final String DEPLOYMENT_TYPE_FIELD = "deploymentType";

    @ConfigProperty(
        name = "quantum.realmConfig.systemRealm",
        defaultValue = "system-com"
    )
    String systemRealm;

    @Override
    public String getId() {
        return "00007";
    }

    @Override
    public String getDbFromVersion() {
        return "1.0.5";
    }

    @Override
    public int getDbFromVersionInt() {
        return 105;
    }

    @Override
    public String getDbToVersion() {
        return "1.0.6";
    }

    @Override
    public int getDbToVersionInt() {
        return 106;
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public String getAuthor() {
        return "Quantum Framework";
    }

    @Override
    public String getName() {
        return "Backfill Realm Deployment Type";
    }

    @Override
    public String getDescription() {
        return "Set missing or null Realm deploymentType values to DEDICATED.";
    }

    @Override
    public String getScope() {
        return "ALL";
    }

    @Override
    public Set<String> getApplicableDatabases() {
        return Set.of(systemRealm);
    }

    @Override
    public void execute(
        MorphiaSession session,
        MongoClient mongoClient,
        MultiEmitter<? super String> emitter
    ) {
        UpdateResult result = session.getDatabase()
            .getCollection(REALM_COLLECTION, Document.class)
            .updateMany(
                or(
                    exists(DEPLOYMENT_TYPE_FIELD, false),
                    eq(DEPLOYMENT_TYPE_FIELD, null)),
                set(
                    DEPLOYMENT_TYPE_FIELD,
                    RealmDeploymentType.DEDICATED.name()));

        log(
            "Realm deployment type migration matched " + result.getMatchedCount()
                + " and modified " + result.getModifiedCount()
                + " realm catalog record(s) in " + session.getDatabase().getName(),
            emitter);
    }
}
