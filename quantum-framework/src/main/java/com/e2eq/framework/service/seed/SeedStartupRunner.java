package com.e2eq.framework.service.seed;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.*;

import com.e2eq.framework.util.EnvConfigUtils;

/**
 * Version-agnostic seed runner that applies latest seed packs at startup,
 * independent of schema migrations. Idempotency and repeatable behavior are
 * guaranteed by MongoSeedRegistry per dataset checksum.
 */
@Startup
@ApplicationScoped
public class SeedStartupRunner {

    @Inject
    MongoClient mongoClient;

    @Inject
    CredentialRepo credRepo;

    @Inject
    SeedLoaderService seedLoaderService;

    @Inject
    SeedDiscoveryService seedDiscoveryService;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    SecurityUtils securityUtils;

    @ConfigProperty(name = "quantum.seed-pack.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "quantum.seed-pack.root", defaultValue = "src/main/resources/seed-packs")
    Optional<String> seedRootConfig;

    @ConfigProperty(name = "quantum.seed-pack.apply.on-startup", defaultValue = "true")
    boolean applyOnStartup;

    @ConfigProperty(name = "quantum.seed.apply.filter")
    Optional<String> seedFilterCsv;

    @PostConstruct
    public void onStart() {
        if (!enabled || !applyOnStartup) {
            Log.info("SeedStartupRunner disabled by configuration (quantum.seed-pack.enabled / quantum.seed-pack.apply.on-startup)");
            return;
        }
        // Run for system realm, and if distinct, also default and test
        List<String> realms = new ArrayList<>();
        String system = envConfigUtils.getSystemRealm();
        String def = envConfigUtils.getDefaultRealm();
        String test = envConfigUtils.getTestRealm();
        realms.add(system);
        if (!Objects.equals(system, def)) realms.add(def);
        if (!Objects.equals(system, test) && !Objects.equals(def, test)) realms.add(test);
        for (String realm : realms) {
            try {
                // Wait for database to be ready before applying seeds
                waitForDatabase(realm);
                applySeedsIfNeeded(realm);
            } catch (Exception e) {
                Log.warnf("SeedStartupRunner: failed applying seeds for realm %s: %s", realm, e.getMessage());
            }
        }
    }

    /**
     * Wait for the database to be created and ready.
     * This ensures migrations have had a chance to create the database before seeds run.
     */
    private void waitForDatabase(String realm) {
        int maxRetries = 10;
        int retryDelayMs = 500;
        for (int i = 0; i < maxRetries; i++) {
            try {
                List<String> databaseNames = mongoClient.listDatabaseNames().into(new ArrayList<>());
                if (databaseNames.contains(realm)) {
                    // Database exists, check if it has any collections (indicating it's been initialized)
                    var db = mongoClient.getDatabase(realm);
                    var collections = db.listCollectionNames().into(new ArrayList<>());
                    if (!collections.isEmpty() || i >= maxRetries - 1) {
                        // Database exists and has collections, or we've exhausted retries
                        Log.debugf("SeedStartupRunner: database %s is ready", realm);
                        return;
                    }
                }
                // Database doesn't exist yet, wait and retry
                if (i < maxRetries - 1) {
                    Log.debugf("SeedStartupRunner: waiting for database %s to be created (attempt %d/%d)", realm, i + 1, maxRetries);
                    Thread.sleep(retryDelayMs);
                }
            } catch (Exception e) {
                Log.debugf("SeedStartupRunner: error checking database %s: %s (attempt %d/%d)", realm, e.getMessage(), i + 1, maxRetries);
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        Log.warnf("SeedStartupRunner: database %s may not be ready, proceeding anyway", realm);
    }

    private void applySeedsIfNeeded(String realm) throws Exception {
        DistributedLock lock = getSeedLock(realm);
        lock.acquire();
        try {
            // Derive tenantId from realm (replace the last '-' with '.') and resolve admin userId
            String tenantId = realmToTenantId(realm);
            String adminUserId = String.format("admin@%s", tenantId);

            // Lookup admin user profile in the target realm to obtain its dataDomain without requiring SecurityContext
            CredentialUserIdPassword adminCred = null;
            try {
               Optional<CredentialUserIdPassword> oAdminCred = credRepo.findByUserId(adminUserId, envConfigUtils.getSystemRealm(), true);
               if (!oAdminCred.isPresent()) {
                   Log.warnf("SeedStartupRunner: admin user %s not found in realm %s. Seeding will proceed with system context",
                      adminUserId, credRepo.getDatabaseName());
                      oAdminCred = credRepo.findByUserId(envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm(), true);
                      if (!oAdminCred.isPresent()) {
                          Log.warnf("SeedStartupRunner: system user %s not found in realm %s. Seeding will proceed with realm-only context; tenant substitutions may be blank.",
                             envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm());
                      } else {
                         adminCred = oAdminCred.get();
                      }
               } else {
                  adminCred = oAdminCred.get();
               }

            } catch (Exception e) {
                Log.warnf("SeedStartupRunner: error looking up admin user in realm %s: %s", realm, e.getMessage());
            }
            if (adminCred == null) {
                Log.warnf("SeedStartupRunner: admin user %s not found in realm %s. Seeding will proceed with realm-only context; tenant substitutions may be blank.", adminUserId, realm);
            }

            // Build SeedContext with values from admin's dataDomain when available
            SeedContext.Builder ctxBuilder = SeedContext.builder(realm);
            DataDomain adminDD = (adminCred != null) ? adminCred.getDataDomain() : null;

            String resolvedOwnerId = null;
             if (adminCred != null ) {
                    resolvedOwnerId = adminCred.getUserId();
             }

            if (adminDD != null) {
                ctxBuilder
                        .tenantId(adminDD.getTenantId())
                        .orgRefName(adminDD.getOrgRefName())
                        .accountId(adminDD.getAccountNum())
                        .ownerId(adminDD.getOwnerId());
            }
            SeedContext context = ctxBuilder.build();

            // Establish a temporary SecurityContext based on the admin user so repo-layer substitutions resolve correctly
            com.e2eq.framework.model.securityrules.PrincipalContext principalContext = null;
            com.e2eq.framework.model.securityrules.ResourceContext resourceContext = null;
            if (adminDD != null) {
                String pcUserId = (resolvedOwnerId != null && !resolvedOwnerId.isBlank()) ? resolvedOwnerId : adminUserId;
                principalContext = new com.e2eq.framework.model.securityrules.PrincipalContext.Builder()
                        .withDefaultRealm(realm)
                        .withDataDomain(adminDD)
                        .withUserId(pcUserId)
                        .withRoles(new String[]{"admin"})
                        .withScope("systemGenerated")
                        .build();
                resourceContext = new com.e2eq.framework.model.securityrules.ResourceContext.Builder()
                        .withRealm(realm)
                        .withArea("SEED")
                        .withFunctionalDomain("SEED")
                        .withAction("APPLY")
                        .build();
            } else {
                Log.warnf("SeedStartupRunner: failed to establish SecurityContext for admin user %s in realm %s attempting to use system@system.com", adminUserId, realm);
                principalContext = securityUtils.getSystemPrincipalContext();
                resourceContext = securityUtils.getSystemSecurityResourceContext();
               ctxBuilder
                  .tenantId(envConfigUtils.getSystemTenantId())
                  .orgRefName(envConfigUtils.getSystemOrgRefName())
                  .accountId(envConfigUtils.getSystemAccountNumber())
                  .ownerId(envConfigUtils.getSystemUserId())
                  .build();
            }

            // Use SeedDiscoveryService to discover, filter, and resolve latest versions
            Map<String, SeedPackDescriptor> latestByName;
            try {
                latestByName = seedDiscoveryService.discoverLatestApplicable(
                        context,
                        seedFilterCsv.orElse(null));
            } catch (IOException e) {
                Log.warnf("SeedStartupRunner: failed to discover seed packs for realm %s: %s", realm, e.getMessage());
                return;
            }

            if (latestByName.isEmpty()) {
                Log.infof("SeedStartupRunner: no applicable seed packs for realm %s", realm);
                return;
            }

            // Apply via SeedLoaderService (CDI-managed, uses MorphiaSeedRegistry for idempotency)
            List<SeedPackRef> refs = new ArrayList<>();
            latestByName.values().forEach(d -> refs.add(SeedPackRef.exact(d.getManifest().getSeedPack(), d.getManifest().getVersion())));
            Log.infof("SeedStartupRunner: applying %d seed pack(s) to realm %s", refs.size(), realm);

            SecurityCallScope.runWithContexts(principalContext, resourceContext, () -> {
                seedLoaderService.applySeeds(context, refs);
            });
        } finally {
            // Always clear any thread-local security contexts we may have set
            try { com.e2eq.framework.model.securityrules.SecurityContext.clear(); } catch (Exception ignored) {}
            lock.release();
        }
    }

    private static String realmToTenantId(String realm) {
        if (realm == null || realm.isBlank()) return realm;
        int idx = realm.lastIndexOf('-');
        if (idx < 0) return realm; // fallback
        return realm.substring(0, idx) + "." + realm.substring(idx + 1);
    }


    private DistributedLock getSeedLock(String realm) {
        MongoCollection<Document> collection = mongoClient
                .getDatabase("sherlock")
                .getCollection("locks");
        Sherlock sherlock = MongoSherlock.create(collection);
        return sherlock.createLock(String.format("seed-lock-%s", realm));
    }
}
