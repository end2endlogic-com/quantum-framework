package com.e2eq.framework.model.persistent.morphia.changesets;


import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoClient;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class AddAnonymousSecurityRules extends ChangeSetBase {

    @Inject
    PolicyRepo policyRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    SecurityUtils securityUtils;

    @Override
    public String getId() {
        return "00002";
    }

    @Override
    public String getDbFromVersion() {
        return "1.0.1";
    }
    @Override
    public int getDbFromVersionInt() {
        return 101;
    }

    @Override
    public String getDbToVersion() {
        return "1.0.2";
    }

    @Override
    public int getDbToVersionInt() {
        return 102;
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
    public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) throws Exception {
        Log.infof("Adding Anonymouse Security Rules: %s", session.getDatabase().getName());
        emitter.emit(String.format("Adding Anonymous Security Rules: %s", session.getDatabase().getName()));

        // check if the policies already exist
        if (!policyRepo.findByRefName(session, "defaultAnonymousPolicy").isPresent()) {
            // So this will match any user that has the role "user"
            // for "any area, any domain, and any action i.e. all areas, domains, and actions
            SecurityURIHeader header = new SecurityURIHeader.Builder()
                    .withIdentity(envConfigUtils.getAnonymousUserId())      // with the role "user"
                    .withArea("website")             // any area
                    .withFunctionalDomain("contactUs") // any domain
                    .withAction("create")           // any action
                    .build();

            // This will match the resources
            // from "any" account, in the "e2e" realm, any tenant, any owner, any datasegment
            SecurityURIBody body = new SecurityURIBody.Builder()
                    .withAccountNumber(envConfigUtils.getSystemAccountNumber()) // any account
                    .withRealm(envConfigUtils.getSystemRealm()) // within just the realm
                    .withTenantId(SecurityUtils.any) // any tenant
                    .withOwnerId(SecurityUtils.any) // any owner
                    .withOrgRefName(envConfigUtils.getSystemOrgRefName())
                    .withDataSegment(SecurityUtils.any) // any datasegement
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
            defaultAnonymousPolicy.setPrincipalId(envConfigUtils.getAnonymousUserId());
            defaultAnonymousPolicy.setDisplayName("anonymous policy");
            defaultAnonymousPolicy.setDescription("anonymous users can register and fill out a contact us form");
            defaultAnonymousPolicy.getRules().add(r);
            defaultAnonymousPolicy.setRefName("defaultAnonymousPolicy");

            policyRepo.save(session, defaultAnonymousPolicy);
        }

    }
}
