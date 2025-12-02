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
import com.e2eq.framework.service.seed.*;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.EnvConfigUtils;
import com.mongodb.client.MongoClient;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.*;

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

    @Inject SeedLoaderService seedLoaderService;

    @Inject SeedDiscoveryService seedDiscoveryService;

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
        return provisionTenant(tenantEmailDomain, orgRefName, accountId, adminUserId, adminSubject, adminPassword, java.util.List.of());
    }

    /**
     * Provisions a new tenant and applies the specified seed archetypes.
     * @param archetypes list of archetype names to apply after migrations
     */
    public ProvisionResult provisionTenant(String tenantEmailDomain,
                                           String orgRefName,
                                           String accountId,
                                           String adminUserId,
                                           String adminSubject,
                                           String adminPassword,
                                           List<String> archetypes) {
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
        Log.infof("  computed realmId: %s", realmId);

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
           Log.warnf("Realm %s already exists in the realm collection in realm %s", realmId, systemRealm);
            Realm existing = existingOpt.get();
           List<String> diffs = new ArrayList<>();
           if (!Objects.equals(existing.getEmailDomain(), desiredRealm.getEmailDomain())) {
              diffs.add(String.format("emailDomain: existing='%s', requested='%s'",
                 existing.getEmailDomain(), desiredRealm.getEmailDomain()));
           }
           if (!Objects.equals(existing.getDatabaseName(), desiredRealm.getDatabaseName())) {
              diffs.add(String.format("databaseName: existing='%s', requested='%s'",
                 existing.getDatabaseName(), desiredRealm.getDatabaseName()));
           }
           if (!Objects.equals(existing.getDefaultAdminUserId(), desiredRealm.getDefaultAdminUserId())) {
              diffs.add(String.format("defaultAdminUserId: existing='%s', requested='%s'",
                 existing.getDefaultAdminUserId(), desiredRealm.getDefaultAdminUserId()));
           }
           if (!Objects.equals(existing.getDomainContext(), desiredRealm.getDomainContext())) {
              diffs.add(String.format("domainContext: existing='%s', requested='%s'",
                 existing.getDomainContext(), desiredRealm.getDomainContext()));
           }

           if (!diffs.isEmpty()) {
              throw new IllegalStateException(
                 "Realm already exists; differences detected: " + String.join("; ", diffs));
           }
            if (!diffs.isEmpty()) {
               StringBuffer bdiffs = new StringBuffer();
               for (String diff : diffs) {
                  Log.errorf("Diff: %s", diff);
                  bdiffs.append(diff).append("\n");
               }
                String text ="Realm already exists, and the request would modify it. Aborting." + bdiffs.toString();
                Log.error(text);
                throw new IllegalStateException(text);
            }
            Log.warnf("Realm %s already exists in system catalog; proceeding idempotently.", realmId);
            result.addWarning("Realm already exists; no catalog changes were made.");
        } else {
            // Save realm in the system realm catalog
            realmRepo.save(systemRealm, desiredRealm); // save in the system realm, the new record
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

        // Build a principal/resource context for the newly created tenant to ensure
        // that migrations and seeds operate on the correct tenant database.
        PrincipalContext tenantPrincipal = new PrincipalContext.Builder()
                .withDefaultRealm(realmId)
                .withDataDomain(dataDomain)
                .withUserId(adminUserId)
                .withRoles(new String[]{"admin"})
                .withScope("TenantProvisioning")
                .build();

        ResourceContext migrationResource = new ResourceContext.Builder()
                .withRealm(realmId)
                .withArea("MIGRATION")
                .withFunctionalDomain("DB")
                .withAction("APPLY")
                .build();

        SecurityCallScope.runWithContexts(tenantPrincipal, migrationResource, () -> {
            migrationService.runAllUnRunMigrations(realmId, emitter);
        });

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
                throw new IllegalStateException(String.format("Admin user exists but credential with UserId: %s was not found in credential repo in realm: %s.", adminUserId, systemRealm));
            }
            CredentialUserIdPassword cred = credOpt.get();
            Set<String> storedRoles = cred.getRoles() == null ? java.util.Collections.emptySet() : new HashSet<>(Arrays.asList(cred.getRoles()));
            boolean attributesMatch = Objects.equals(cred.getSubject(), adminSubject)
                    && Objects.equals(storedRoles, desiredRoles)
                    && Objects.equals(Boolean.FALSE, cred.getForceChangePassword())
                    && Objects.equals(cred.getDomainContext(), dc);
            if (!attributesMatch) {
               List<String> diffs = new ArrayList<>();
               if (!Objects.equals(cred.getSubject(), adminSubject)) {
                  diffs.add(String.format("subject: existing='%s', requested='%s'",
                         cred.getSubject(), adminSubject));
               }
               if (!Objects.equals(storedRoles, desiredRoles)) {
                  diffs.add(String.format("roles: existing='%s', requested='%s'",
                         storedRoles, desiredRoles));
               }
               if (!Objects.equals(Boolean.FALSE, cred.getForceChangePassword())) {
                  diffs.add(String.format("forceChangePassword: existing='%s', requested='%s'",
                         cred.getForceChangePassword(), Boolean.FALSE));
               }
               if (!Objects.equals(cred.getDomainContext(), dc)) {
                  diffs.add(String.format("domainContext: existing='%s', requested='%s'",
                         cred.getDomainContext(), dc));
               }
               StringBuffer btext = new StringBuffer();
               for (String diff : diffs) {
                  btext.append(diff).append("\n");
               }
               String text = String.format("Admin user already exists with different attributes. Diffs:%s", btext.toString());
                throw new IllegalStateException(text);
            }
            Log.warnf("Admin user %s already exists in realm %s; proceeding idempotently.", adminUserId, realmId);
            result.addWarning("Admin user already exists; no user changes were made.");
        }

        // first apply any seed that does not have a archetype but
       // is a perTenant Seed.
       SeedContext ctx = SeedContext.builder(realmId)
                            .tenantId(dc.getTenantId())
                            .orgRefName(dc.getOrgRefName())
                            .accountId(dc.getAccountId())
                            .ownerId(adminUserId)
                            .build();

       // Discover latest applicable packs for this context using SeedDiscoveryService
       Map<String, SeedPackDescriptor> latestByPack;
       try {
          latestByPack = seedDiscoveryService.discoverLatestApplicable(ctx, null);
       } catch (IOException e) {
          throw new IllegalStateException("Failed to discover seed packs: " + e.getMessage(), e);
       }

       // Apply all applicable (GLOBAL/unscoped and any per-tenant that match)
       List<SeedPackRef> baseRefs = latestByPack.values().stream()
                                       .map(d -> SeedPackRef.exact(d.getManifest().getSeedPack(), d.getManifest().getVersion()))
                                       .toList();
       if (!baseRefs.isEmpty()) {
          Log.infof("Applying %d base seed pack(s) (GLOBAL/unscoped/per-tenant) to realm %s", baseRefs.size(), realmId);
          // Run seeds under tenant security context
          ResourceContext seedResource = new ResourceContext.Builder()
                  .withRealm(realmId)
                  .withArea("SEED")
                  .withFunctionalDomain("SEED")
                  .withAction("APPLY")
                  .build();
          SecurityCallScope.runWithContexts(tenantPrincipal, seedResource, () -> {
              seedLoaderService.applySeeds(ctx, baseRefs);
          });
       }


       // 5) Apply requested seed archetypes (optional)
        if (archetypes != null && !archetypes.isEmpty()) {

           /* loader = SeedLoader.builder()
                    .addSeedSource(new FileSeedSource("files", SeedPathResolver.resolveSeedRoot()))
                    .seedRepository(new MongoSeedRepository(mongoClient))
                    .seedRegistry(new MongoSeedRegistry(mongoClient))
                    .build();
             ctx = SeedContext.builder(realmId)
                    .tenantId(dc.getTenantId())
                    .orgRefName(dc.getOrgRefName())
                    .accountId(dc.getAccountId())
                    .ownerId(adminUserId)
                    .build(); */
            for (String archetype : archetypes) {
                String at = archetype == null ? null : archetype.trim();
                if (at == null || at.isEmpty()) continue;
                Log.infof("Applying seed archetype '%s' to realm %s", at, realmId);
                ResourceContext seedResource = new ResourceContext.Builder()
                        .withRealm(realmId)
                        .withArea("SEED")
                        .withFunctionalDomain("SEED")
                        .withAction("APPLY")
                        .build();
                SecurityCallScope.runWithContexts(tenantPrincipal, seedResource, () -> {
                    // Apply requested archetype via SeedLoaderService
                    seedLoaderService.applyArchetype(ctx, at); // will throw if not found
                });
            }
        } else {
           Log.info("No Archetypes applicable");
        }

        // 6) Idempotent: Apply indexes and verify (under tenant migration context)
        SecurityCallScope.runWithContexts(tenantPrincipal, migrationResource, () -> {
            migrationService.applyIndexes(realmId);
            migrationService.checkInitialized(realmId);
        });

        return result;
    }
}
