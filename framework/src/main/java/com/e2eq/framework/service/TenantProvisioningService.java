package com.e2eq.framework.service;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.persistent.security.Realm;
import com.e2eq.framework.model.security.auth.UserManagement;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    @Inject UserManagement userManagement;
    @Inject SecurityUtils securityUtils;

    /**
     * Provisions a new tenant.
     *
     * @param tenantEmailDomain e.g. "acme.com"
     * @param orgRefName        e.g. "acme.com"
     * @param accountId         e.g. initial account number for the tenant
     * @param adminUserId       e.g. "admin@acme.com"
     * @param adminUsername     display/login name
     * @param adminPassword     plaintext password to be hashed and stored
     * @return the created realmId (database name)
     */
    public String provisionTenant(String tenantEmailDomain,
                                  String orgRefName,
                                  String accountId,
                                  String adminUserId,
                                  String adminUsername,
                                  String adminPassword) {
        Objects.requireNonNull(tenantEmailDomain, "tenantEmailDomain cannot be null");
        Objects.requireNonNull(orgRefName, "orgRefName cannot be null");
        Objects.requireNonNull(accountId, "accountId cannot be null");
        Objects.requireNonNull(adminUserId, "adminUserId cannot be null");
        Objects.requireNonNull(adminUsername, "adminUsername cannot be null");
        Objects.requireNonNull(adminPassword, "adminPassword cannot be null");

        // 1) Compute realm id
        String realmId = tenantEmailDomain.replace('.', '-');

        // 2) Prevent duplicates in the system catalog (system realm)
        String systemRealm = securityUtils.getSystemRealm();
        Optional<Realm> existing = realmRepo.findByEmailDomain(tenantEmailDomain, true, systemRealm);
        if (existing.isPresent()) {
            throw new IllegalStateException("A realm for tenant domain already exists: " + tenantEmailDomain);
        }

        // Build DomainContext
        DomainContext dc = DomainContext.builder()
                .tenantId(tenantEmailDomain)
                .orgRefName(orgRefName)
                .accountId(accountId)
                .defaultRealm(realmId)
                .build();

        // Create Realm record
        Realm realm = Realm.builder()
                .refName(realmId)
                .displayName(realmId)
                .emailDomain(tenantEmailDomain)
                .databaseName(realmId)
                .domainContext(dc)
                .defaultAdminUserId(adminUserId)
                .build();

        // Save realm in the system realm catalog
        realmRepo.save(systemRealm, realm);
        Log.infof("Created realm catalog entry for %s in system realm %s", realmId, systemRealm);

        // 3) Run migrations in the new realm DB with a lightweight proxy-emitter for logging
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

        // 4) Create initial tenant admin credentials inside the new realm
        Set<String> roles = Set.of("admin", "user");
        userManagement.createUser(
                realmId,
                adminUserId,
                adminPassword,
                Boolean.FALSE,
                adminUsername,
                roles,
                dc
        );

        // 5) Optional: Apply indexes and verify
        migrationService.applyIndexes(realmId);
        migrationService.checkInitialized(realmId);

        return realmId;
    }

}
