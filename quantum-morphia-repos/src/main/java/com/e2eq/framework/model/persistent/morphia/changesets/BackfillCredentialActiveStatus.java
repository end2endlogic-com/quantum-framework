package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.mongodb.client.MongoClient;
import com.mongodb.client.result.UpdateResult;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Set;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Updates.set;

/**
 * Backfills the credential lifecycle status introduced after legacy credential
 * rows already existed.  Login still treats null as legacy-allowed, but admin
 * surfaces should not have to render an UNKNOWN state for usable credentials.
 */
@Startup
@ApplicationScoped
public class BackfillCredentialActiveStatus extends ChangeSetBase {

    static final String CREDENTIAL_COLLECTION = "credentialUserIdPassword";
    static final String ACTIVE_STATUS_FIELD = "activeStatus";

    @ConfigProperty(
        name = "quantum.realmConfig.systemRealm",
        defaultValue = "system-com"
    )
    String systemRealm;

    @Override
    public String getId() {
        return "00008";
    }

    @Override
    public String getDbFromVersion() {
        return "1.0.6";
    }

    @Override
    public int getDbFromVersionInt() {
        return 106;
    }

    @Override
    public String getDbToVersion() {
        return "1.0.7";
    }

    @Override
    public int getDbToVersionInt() {
        return 107;
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
        return "Backfill Credential Active Status";
    }

    @Override
    public String getDescription() {
        return "Set missing or null CredentialUserIdPassword activeStatus values to ACTIVE.";
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
            .getCollection(CREDENTIAL_COLLECTION, Document.class)
            .updateMany(
                or(
                    exists(ACTIVE_STATUS_FIELD, false),
                    eq(ACTIVE_STATUS_FIELD, null)),
                set(
                    ACTIVE_STATUS_FIELD,
                    ActiveStatus.ACTIVE.name()));

        log(
            "Credential activeStatus migration matched " + result.getMatchedCount()
                + " and modified " + result.getModifiedCount()
                + " credential record(s) in " + session.getDatabase().getName(),
            emitter);
    }
}
