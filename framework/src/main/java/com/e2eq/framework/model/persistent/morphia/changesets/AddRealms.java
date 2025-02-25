package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.persistent.security.Realm;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class AddRealms implements ChangeSetBean  {
    @Inject
    RealmRepo realmRepo;

    @Inject
    RuleContext ruleContext;

    @Override
    public String getId() {
        return "00004";
    }

    @Override
    public Double getDbFromVersion() {
        return Double.valueOf(0.20d);
    }

    @Override
    public Double getDbToVersion() {
        return Double.valueOf(0.30d);
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
    public void execute(String r) throws Exception {
        Log.infof("Adding Default Realm to the database: realm passed to execution: %s", r);
        String[] roles = {"admin"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "realm", "create");
        TestUtils.initRules(ruleContext, "security","realm", TestUtils.systemUserId);
        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
            DomainContext domainContext = DomainContext.builder()
                    .tenantId("system_com")
                    .orgRefName("system_com")
                    .defaultRealm("system_com")
                    .accountId("000001")
                    .build();

            Realm realm = Realm.builder()
                    .refName("system_com")
                    .displayName("system@system.com")
                    .emailDomain("system.com")
                    .databaseName("system_com")
                    .domainContext(domainContext)
                    .build();

            realmRepo.save(realm);
        }
        Log.info("Added Successfully");
    }
}
