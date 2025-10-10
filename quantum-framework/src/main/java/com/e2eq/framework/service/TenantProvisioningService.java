package com.e2eq.framework.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Service to provision a new tenant: creates a Realm, runs migrations on that realm DB,
 * and seeds an initial admin credential in the newly created realm.
 */
@ApplicationScoped
public class TenantProvisioningService {

    @Inject RealmRepo realmRepo;
    @Inject MigrationService migrationService;
    @Inject AuthProviderFactory authProviderFactory;
    @Inject EnvConfigUtils envConfigUtils;
    @Inject CredentialRepo credentialRepo;

    public static class ProvisionResult {
        public String realmId;
        public boolean realmCreated;
        public boolean userCreated;
        public List<String> warnings = new java.util.ArrayList<>();
        public void addWarning(String w) { if (w != null && !w.isBlank()) warnings.add(w); }
    }

    /**
     * Provisions a new tenant.
     *
     * @param tenantEmailDomain e.g. "acme.com"
     * @param orgRefName        e.g. "acme.com"
     * @param accountId         e.g. initial account number for the tenant
     * @param adminUserId       e.g. "admin@acme.com"
     * @param adminSubject      the unique immutable identifier for this user
     * @param adminPassword     plaintext password to be hashed and stored
     * @return details about what happened and any warnings
     */
    public ProvisionResult provisionTenant(String tenantEmailDomain,
                                           String orgRefName,
                                           String accountId,
                                           String adminUserId,
                                           String adminSubject,
                                           String adminPassword) {
        Objects.requireNonNull(tenantEmailDomain, "tenantEmailDomain cannot be null");
        Objects.requireNonNull(orgRefName, "orgRefName cannot be null");
        Objects.requireNonNull(accountId, "accountId cannot be null");
        Objects.requireNonNull(adminUserId, "adminUserId cannot be null");
        Objects.requireNonNull(adminSubject, "adminSubject cannot be null");
        Objects.requireNonNull(adminPassword, "adminPassword cannot be null");

        ProvisionResult result = new ProvisionResult();

        // 1) Compute realm id
        String realmId = tenantEmailDomain.replace('.', '-');
        result.realmId = realmId;

        // 2) Lookup/Prevent duplicates in the system catalog (system realm)
        String systemRealm = envConfigUtils.getSystemRealm();
        Optional<Realm> existingOpt = realmRepo.findByEmailDomain(tenantEmailDomain, true, systemRealm);

        // Build DomainContext desired
        DomainContext dc = DomainContext.builder()
                .tenantId(tenantEmailDomain)
                .orgRefName(orgRefName)
                .accountId(accountId)
                .defaultRealm(realmId)
                .build();

       DataDomain dataDomain = DataDomain.builder()
                       .orgRefName(dc.getOrgRefName())
                       .accountNum(dc.getAccountId())
                       .tenantId(dc.getTenantId())
                       .ownerId(adminUserId)
                       .build();

        // Desired Realm representation
        Realm desiredRealm = Realm.builder()
                .refName(realmId)
                .displayName(realmId)
                .emailDomain(tenantEmailDomain)
                .databaseName(realmId)
                .domainContext(dc)
                .dataDomain(dataDomain)
                .defaultAdminUserId(adminUserId)
                .build();

        if (existingOpt.isPresent()) {
            Realm existing = existingOpt.get();
            boolean same = Objects.equals(existing.getEmailDomain(), desiredRealm.getEmailDomain()) &&
                           Objects.equals(existing.getDatabaseName(), desiredRealm.getDatabaseName()) &&
                           Objects.equals(existing.getDefaultAdminUserId(), desiredRealm.getDefaultAdminUserId()) &&
                           Objects.equals(existing.getDomainContext(), desiredRealm.getDomainContext());
            if (!same) {
                throw new IllegalStateException("Realm already exists, and the request would modify it. Aborting.");
            }
            Log.warnf("Realm %s already exists in system catalog; proceeding idempotently.", realmId);
            result.addWarning("Realm already exists; no catalog changes were made.");
        } else {
            // Save realm in the system realm catalog
            realmRepo.save(systemRealm, desiredRealm);
            Log.infof("Created realm catalog entry for %s in system realm %s", realmId, systemRealm);
            result.realmCreated = true;
        }

        // 3) Run migrations in the realm DB with a lightweight proxy-emitter for logging
        @SuppressWarnings("unchecked")
        MultiEmitter<String> emitter = (MultiEmitter<String>) java.lang.reflect.Proxy.newProxyInstance(
                MultiEmitter.class.getClassLoader(),
                new Class[]{MultiEmitter.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("emit".equals(m) && args != null && args.length == 1 && args[0] instanceof String s) {
                        Log.infof("[TenantProvisioning] %s", s);
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == Void.TYPE) return null;
                    if (rt == boolean.class || rt == Boolean.class) return Boolean.FALSE;
                    if (rt == long.class || rt == Long.class) return 0L;
                    if (MultiEmitter.class.isAssignableFrom(rt)) return proxy;
                    return null;
                }
        );
        migrationService.runAllUnRunMigrations(realmId, emitter);

        // 4) Ensure initial tenant admin credentials inside the realm
        Set<String> desiredRoles = Set.of("admin", "user");
        UserManagement userManagement = authProviderFactory.getUserManager();
        boolean userExists = userManagement.userIdExists(adminUserId);
        if (!userExists) {
           Log.warnf("Creating initial admin user %s with password provided, however since we are creating it the subject passed in %s will be ignored",adminUserId, adminSubject);
            userManagement.createUser(
                    adminUserId,
                    adminPassword,
                    Boolean.FALSE,
                    desiredRoles,
                    dc
            );
            result.userCreated = true;
        } else {
            Optional<CredentialUserIdPassword> credOpt = credentialRepo.findByUserId(adminUserId, systemRealm, true);
            if (credOpt.isEmpty()) {
                throw new IllegalStateException("Admin user exists but cannot be loaded for validation.");
            }
            CredentialUserIdPassword cred = credOpt.get();
            Set<String> storedRoles = cred.getRoles() == null ? java.util.Collections.emptySet() : new HashSet<>(Arrays.asList(cred.getRoles()));
            boolean attributesMatch = Objects.equals(cred.getSubject(), adminSubject)
                    && Objects.equals(storedRoles, desiredRoles)
                    && Objects.equals(Boolean.FALSE, cred.getForceChangePassword())
                    && Objects.equals(cred.getDomainContext(), dc);
            if (!attributesMatch) {
                throw new IllegalStateException("Admin user already exists with different attributes.");
            }
            Log.warnf("Admin user %s already exists in realm %s; proceeding idempotently.", adminUserId, realmId);
            result.addWarning("Admin user already exists; no user changes were made.");
        }

        // 5) Idempotent: Apply indexes and verify
        migrationService.applyIndexes(realmId);
        migrationService.checkInitialized(realmId);

        return result;
    }

}
