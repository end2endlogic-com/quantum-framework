package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.persistent.security.Realm;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Startup
@ApplicationScoped
public class AddRealms implements ChangeSetBean  {
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
    public void execute(MorphiaSession session,  String r) throws Exception {
        Log.infof("Adding Default Realm to the database: realm passed to execution: %s", r);

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

        Log.info(".  Added Successfully");
    }
}
