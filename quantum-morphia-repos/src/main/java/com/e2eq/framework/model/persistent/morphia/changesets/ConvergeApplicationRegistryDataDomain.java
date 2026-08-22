package com.e2eq.framework.model.persistent.morphia.changesets;

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

import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

/** Converges global application-registry rows to the system tenant data domain. */
@Startup
@ApplicationScoped
public class ConvergeApplicationRegistryDataDomain extends ChangeSetBase {

    @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com")
    String systemRealm;

    @ConfigProperty(name = "quantum.systemTenantId", defaultValue = "system.com")
    String systemTenantId;

    @ConfigProperty(name = "quantum.systemOrgRefName", defaultValue = "system.com")
    String systemOrgRefName;

    @ConfigProperty(name = "quantum.systemAccountNumber", defaultValue = "0000000000")
    String systemAccountNumber;

    @Override public String getId() { return "00009"; }
    @Override public String getDbFromVersion() { return "1.0.7"; }
    @Override public int getDbFromVersionInt() { return 107; }
    @Override public String getDbToVersion() { return "1.0.8"; }
    @Override public int getDbToVersionInt() { return 108; }
    @Override public int getPriority() { return 100; }
    @Override public String getAuthor() { return "Quantum Framework"; }
    @Override public String getName() { return "Converge Application Registry Data Domain"; }
    @Override public String getDescription() {
        return "Stamp global application registry rows with the configured system data domain.";
    }
    @Override public String getScope() { return "ALL"; }
    @Override public Set<String> getApplicableDatabases() { return Set.of(systemRealm); }

    @Override
    public void execute(MorphiaSession session, MongoClient mongoClient,
                        MultiEmitter<? super String> emitter) {
        UpdateResult result = session.getDatabase().getCollection("application", Document.class)
            .updateMany(new Document(), combine(
                set("dataDomain.orgRefName", systemOrgRefName),
                set("dataDomain.accountNum", systemAccountNumber),
                set("dataDomain.tenantId", systemTenantId),
                set("dataDomain.dataSegment", 0)));
        log("Application registry data-domain migration matched " + result.getMatchedCount()
            + " and modified " + result.getModifiedCount() + " record(s) in "
            + session.getDatabase().getName(), emitter);
    }
}
