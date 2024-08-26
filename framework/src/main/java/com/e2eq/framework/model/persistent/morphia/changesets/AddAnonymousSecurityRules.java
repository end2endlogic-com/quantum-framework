package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.config.AWSConfig;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.model.persistent.security.Policy;
import com.e2eq.framework.model.persistent.security.Rule;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class AddAnonymousSecurityRules implements ChangeSetBean {

    @Inject
    PolicyRepo policyRepo;

    @Inject
    AWSConfig config;

    @Override
    public String getId() {
        return "00002";
    }

    @Override
    public Double getDbFromVersion() {
        return Double.valueOf(0.10d);
    }

    @Override
    public Double getDbToVersion() {
        return Double.valueOf(0.20d);
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
        return "Add Anonymous Security Rules";
    }

    @Override
    public String getDescription() {
        return "Add Anonymous Security Rules such as registration and contactUs";
    }

    @Override
    public String getScope() {
        return "ALL";
    }

    @Override
    public void execute(String realm) throws Exception {
        if (config.checkMigration().isPresent() && config.checkMigration().get().booleanValue()) {
            // So this will match any user that has the role "user"
            // for "any area, any domain, and any action i.e. all areas, domains, and actions
            SecurityURIHeader header = new SecurityURIHeader.Builder()
                    .withIdentity(SecurityUtils.anonymousUserId)      // with the role "user"
                    .withArea("website")             // any area
                    .withFunctionalDomain("contactUs") // any domain
                    .withAction("create")           // any action
                    .build();

            // This will match the resources
            // from "any" account, in the "b2bi" realm, any tenant, any owner, any datasegment
            SecurityURIBody body = new SecurityURIBody.Builder()
                    .withAccountNumber(SecurityUtils.systemAccountNumber) // any account
                    .withRealm(SecurityUtils.systemRealm) // within just the b2bi realm
                    .withTenantId("*") // any tenant
                    .withOwnerId("*") // any owner
                    .withDataSegment("*") // any datasegement
                    .build();

            // Create the URI that represents this "rule" where by
            // for any one with the role "user", we want to consider this rule base for
            // all resources in the b2bi realm
            SecurityURI uri = new SecurityURI(header, body);

            Rule.Builder b = new Rule.Builder()
                    .withName("allow anonymous users to register and fill out a contact us form")
                    .withSecurityURI(uri)
                    .withEffect(RuleEffect.ALLOW)
                    .withFinalRule(true);
            Rule r = b.build();

            Policy defaultAnonymousPolicy = new Policy();
            defaultAnonymousPolicy.setPrincipalId(SecurityUtils.anonymousUserId);
            defaultAnonymousPolicy.setDisplayName("anonymous policy");
            defaultAnonymousPolicy.setDescription("anonymous users can register and fill out a contact us form");
            defaultAnonymousPolicy.getRules().add(r);
            defaultAnonymousPolicy.setRefName("defaultAnonymousPolicy");
            defaultAnonymousPolicy.setDataDomain(SecurityUtils.systemDataDomain);

            policyRepo.save(defaultAnonymousPolicy);

        } else {
            if (Log.isInfoEnabled())
                Log.warn("Skipping Add AnonymousSecurityRules");
        }
    }
}
