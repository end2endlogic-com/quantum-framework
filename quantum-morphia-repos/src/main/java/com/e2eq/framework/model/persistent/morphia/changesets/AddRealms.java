package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;

import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoClient;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Startup
@ApplicationScoped
public class AddRealms extends ChangeSetBase {
    @Inject
    RealmRepo realmRepo;

    @Inject
    RuleContext ruleContext;

    @Inject
    SecurityUtils securityUtils;

    @ConfigProperty(name = "quantum.realmConfig.systemTenantId", defaultValue = "system.com")
    String systemTenantId;

    @ConfigProperty(name = "quantum.realmConfig.systemOrgRefName", defaultValue = "system.com")
    String systemOrgRefName;

    @ConfigProperty(name = "quantum.realmConfig.defaultRealm", defaultValue = "mycompanyxyz-com")
    String defaultRealm;

    @ConfigProperty(name = "quantum.realmConfig.systemAccountNumber", defaultValue = "0000000000")
    String accountNumber;

    @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com")
    String systemRealm;

   @ConfigProperty(name = "quantum.realmConfig.testRealm", defaultValue = "test-quantum-com")
    String testRealm;
   @ConfigProperty(name = "quantum.realmConfig.testTenantId", defaultValue = "test-quantum.com")
   String testTenantId;
   @ConfigProperty(name = "quantum.realmConfig.testOrgRefName", defaultValue = "test-quantum.com")
   String testOrgRefName;
   @ConfigProperty(name = "quantum.realmConfig.testAccountNumber", defaultValue = "0000000001")
   String testAccountNumber;


    @Override
    public String getId() {
        return "00004";
    }

    @Override
    public String getDbFromVersion() {
        return "1.0.2";
    }

    @Override
    public int getDbFromVersionInt() {
        return 102;
    }

    @Override
    public String getDbToVersion() {
        return "1.0.3";
    }

    @Override
    public int getDbToVersionInt() {
        return 103;
    }


    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public String getAuthor() {
        return "Michael Ingardia";
    }

    @Override
    public String getName() {
        return "Add Default Realm";
    }

    @Override
    public String getDescription() {
        return "Add system@system.com realm";
    }

    @Override
    public String getScope() {
        return "ALL";
    }

    @Override
    public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) throws Exception {
        Log.infof("Adding Default Realm to the database: realm passed to execution: %s", session.getDatabase().getName());
        emitter.emit(String.format("Adding Default Realm to the database: realm passed to execution: %s", session.getDatabase().getName()));

        // check if realms already exist
        if (realmRepo.findByEmailDomain(systemTenantId, true).isPresent()) {
            Log.infof("Realm %s already exists. Skipping", systemTenantId);
            emitter.emit("Realm " + systemTenantId + " already exists. Skipping");
            return;
        }
        DomainContext domainContext = DomainContext.builder()
                .tenantId(systemTenantId)
                .orgRefName(systemOrgRefName)
                .defaultRealm(defaultRealm)
                .accountId(accountNumber)
                .build();

        Realm realm = Realm.builder()
                .refName(systemRealm)
                .displayName(systemRealm)
                .emailDomain(systemTenantId)
                .databaseName(systemRealm)
                .domainContext(domainContext)
                .build();

        realmRepo.save(session, realm);

        Log.info(".  Added system-com realm Successfully");
        emitter.emit(".  Added system-com realm Successfully");

        // now add the test-quantum-com realm as well
        domainContext = DomainContext.builder()
                .tenantId(testTenantId)
                .orgRefName(testOrgRefName)
                .defaultRealm(testRealm)
                .accountId(testAccountNumber)
                .build();
        realm = Realm.builder()
                .refName(testTenantId)
                .displayName(testRealm)
                .emailDomain(testTenantId)
                .databaseName(testRealm)
                .domainContext(domainContext)
                .build();
        realmRepo.save(session, realm);

       Log.info(".  Added test-quantum-com realm Successfully");
       emitter.emit(".  Added test-quantum-com realm Successfully");
    }
}
