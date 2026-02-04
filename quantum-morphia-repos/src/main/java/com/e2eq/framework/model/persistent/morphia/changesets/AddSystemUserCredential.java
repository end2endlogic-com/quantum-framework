package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.e2eq.framework.model.security.DomainContext;
import com.mongodb.client.MongoClient;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Set;
import java.util.Collections;

/**
 * Ensures the configured system user (quantum.realmConfig.systemUserId) exists in the
 * credential collection of the system realm so login with the default system user works.
 * Uses the configured auth provider (e.g. custom JWT) to create the user; only creates
 * when the user does not already exist (otherwise confirms database is valid and skips).
 */
@Startup
@ApplicationScoped
public class AddSystemUserCredential extends ChangeSetBase {

    @Inject
    AuthProviderFactory authProviderFactory;

    @ConfigProperty(name = "quantum.realmConfig.systemUserId", defaultValue = "system@system.com")
    String systemUserId;

    @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com")
    String systemRealm;

    @ConfigProperty(name = "quantum.realmConfig.systemTenantId", defaultValue = "system.com")
    String systemTenantId;

    @ConfigProperty(name = "quantum.realmConfig.systemOrgRefName", defaultValue = "system.com")
    String systemOrgRefName;

    @ConfigProperty(name = "quantum.realmConfig.systemAccountNumber", defaultValue = "0000000000")
    String systemAccountNumber;

    /** Initial password for the system user when created by this migration. Set via application.properties. */
    @ConfigProperty(name = "quantum.defaultSystemPassword", defaultValue = "test123456")
    String defaultSystemPassword;

    @Override
    public String getId() {
        return "00005";
    }

    @Override
    public String getDbFromVersion() {
        return "1.0.3";
    }

    @Override
    public int getDbFromVersionInt() {
        return 103;
    }

    @Override
    public String getDbToVersion() {
        return "1.0.4";
    }

    @Override
    public int getDbToVersionInt() {
        return 104;
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
        return "Add System User Credential";
    }

    @Override
    public String getDescription() {
        return "Create system user credential in credentials collection for configured systemUserId so login works.";
    }

    @Override
    public String getScope() {
        return "ALL";
    }

    /**
     * System user credential belongs only in the system realm. Running this change set
     * for other realms would attempt to create the same user in the system realm again,
     * causing E11000 duplicate key on userId.
     */
    @Override
    public Set<String> getApplicableDatabases() {
        return Collections.singleton(systemRealm);
    }

    @Override
    public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) throws Exception {
        String realmName = session.getDatabase().getName();
        Log.infof("AddSystemUserCredential: ensuring system user %s exists in realm %s", systemUserId, realmName);
        emitter.emit(String.format("AddSystemUserCredential: realm=%s, systemUserId=%s", realmName, systemUserId));

        UserManagement userManagement = authProviderFactory.getUserManager();
        if (userManagement.userIdExists(systemUserId)) {
            Log.infof("System user %s already exists in %s; database valid, skipping", systemUserId, realmName);
            emitter.emit("System user already exists; database valid, skipping");
            return;
        }

        DomainContext domainContext = DomainContext.builder()
                .tenantId(systemTenantId)
                .orgRefName(systemOrgRefName)
                .defaultRealm(systemRealm)
                .accountId(systemAccountNumber)
                .build();

        userManagement.createUser(
                systemUserId,
                defaultSystemPassword,
                false,
                Set.of("system", "admin", "user"),
                domainContext);
        Log.infof("Created system user %s in realm %s via configured auth provider", systemUserId, realmName);
        emitter.emit(String.format("Created system user %s in realm %s via configured auth provider", systemUserId, realmName));
    }
}
