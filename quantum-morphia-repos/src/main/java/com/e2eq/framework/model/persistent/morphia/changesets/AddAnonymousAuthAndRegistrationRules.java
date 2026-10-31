package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
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
public class AddAnonymousAuthAndRegistrationRules extends ChangeSetBase {

    private static final String DEFAULT_ANONYMOUS_POLICY = "defaultAnonymousPolicy";

    @Inject
    PolicyRepo policyRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Override
    public String getId() {
        return "00006";
    }

    @Override
    public String getDbFromVersion() {
        return "1.0.4";
    }

    @Override
    public int getDbFromVersionInt() {
        return 104;
    }

    @Override
    public String getDbToVersion() {
        return "1.0.5";
    }

    @Override
    public int getDbToVersionInt() {
        return 105;
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
        return "Add Anonymous Auth and Registration Rules";
    }

    @Override
    public String getDescription() {
        return "Persist anonymous policies for the explicit public authentication and registration repo gates.";
    }

    @Override
    public String getScope() {
        return "ALL";
    }

    @Override
    public int getChangeSetVersion() {
        return 4;
    }

    @Override
    public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) {
        String realm = session.getDatabase().getName();
        Log.infof("Adding anonymous auth and registration security rules: %s", realm);
        emitter.emit(String.format("Adding anonymous auth and registration security rules: %s", realm));

        Policy policy = policyRepo.findByRefName(session, DEFAULT_ANONYMOUS_POLICY).orElseGet(this::newAnonymousPolicy);
        ensureExplicitScope(policy);
        addRuleIfMissing(policy, "SECURITY", "CREDENTIAL_USERID_PASSWORD", "authenticate",
                "allow anonymous users to authenticate through explicit login scope");
        addRuleIfMissing(policy, "SECURITY", "APPLICATION_REGISTRATION", "view",
                "allow anonymous users to inspect registration availability through explicit registration scope");
        addRuleIfMissing(policy, "SECURITY", "APPLICATION_REGISTRATION", "create",
                "allow anonymous users to create registration requests through explicit registration scope");

        policyRepo.save(session, policy);
    }

    private Policy newAnonymousPolicy() {
        Policy policy = new Policy();
        policy.setPrincipalId(envConfigUtils.getAnonymousUserId());
        policy.setDisplayName("anonymous policy");
        policy.setDescription("Anonymous users may use only explicitly scoped public platform entry points.");
        policy.setRefName(DEFAULT_ANONYMOUS_POLICY);
        policy.setApplicationId(SecurityUtils.any);
        policy.setRealmRefName(SecurityUtils.any);
        policy.setPrincipalType(Policy.PrincipalType.ROLE);
        return policy;
    }

    private void ensureExplicitScope(Policy policy) {
        if (policy.getApplicationId() == null || policy.getApplicationId().isBlank()) {
            policy.setApplicationId(SecurityUtils.any);
        }
        if (policy.getRealmRefName() == null || policy.getRealmRefName().isBlank()) {
            policy.setRealmRefName(SecurityUtils.any);
        }
        if (policy.getPrincipalType() == null) {
            policy.setPrincipalType(Policy.PrincipalType.ROLE);
        }
    }

    private void addRuleIfMissing(Policy policy, String area, String domain, String action, String name) {
        boolean exists = policy.getRules().stream()
                .map(Rule::getSecurityURI)
                .filter(uri -> uri != null && uri.getHeader() != null)
                .anyMatch(uri -> matches(uri.getHeader(), area, domain, action));
        if (exists) {
            return;
        }
        policy.getRules().add(buildAnonymousRule(area, domain, action, name));
    }

    private boolean matches(SecurityURIHeader header, String area, String domain, String action) {
        return area.equalsIgnoreCase(header.getArea())
                && domain.equalsIgnoreCase(header.getFunctionalDomain())
                && action.equalsIgnoreCase(header.getAction());
    }

    private Rule buildAnonymousRule(String area, String domain, String action, String name) {
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity(envConfigUtils.getAnonymousUserId())
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .build();

        SecurityURIBody body = new SecurityURIBody.Builder()
                .withAccountNumber(envConfigUtils.getSystemAccountNumber())
                .withRealm(envConfigUtils.getSystemRealm())
                .withTenantId(SecurityUtils.any)
                .withOwnerId(SecurityUtils.any)
                .withOrgRefName(envConfigUtils.getSystemOrgRefName())
                .withDataSegment(SecurityUtils.any)
                .build();

        return new Rule.Builder()
                .withName(name)
                .withSecurityURI(new SecurityURI(header, body))
                .withEffect(RuleEffect.ALLOW)
                .withFinalRule(true)
                .build();
    }
}
