package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.*;

import com.e2eq.framework.util.EnvConfigUtils;

/**
 * Logs a summary of pending seed packs at application startup for key realms.
 */
@Startup
@ApplicationScoped
public class PendingSeedsStartupLogger {

    @Inject
    SeedDiscoveryService seedDiscoveryService;

    @Inject
    SeedRegistry seedRegistry;

    @Inject
    EnvConfigUtils envConfigUtils;


    @jakarta.annotation.PostConstruct
    void onStart() {
        List<String> realms = new ArrayList<>();
        // Log for system, default, and test realms if they differ
        realms.add(envConfigUtils.getSystemRealm());
        if (!envConfigUtils.getSystemRealm().equals(envConfigUtils.getDefaultRealm())) {
            realms.add(envConfigUtils.getDefaultRealm());
        }
        if (!envConfigUtils.getSystemRealm().equals(envConfigUtils.getTestRealm()) &&
            !envConfigUtils.getDefaultRealm().equals(envConfigUtils.getTestRealm())) {
            realms.add(envConfigUtils.getTestRealm());
        }
        for (String realm : realms) {
            try {
                int pending = countPending(realm);
                if (pending == 0) {
                    Log.infof("SeedPacks: No pending seed packs for realm %s.", realm);
                } else {
                    Log.warnf("SeedPacks: %d pending seed pack(s) for realm %s.", pending, realm);
                }
            } catch (Exception e) {
                Log.warnf("SeedPacks: Failed to check pending seed packs for realm %s: %s", realm, e.getMessage());
            }
        }
    }

    private int countPending(String realm) throws IOException {
        Log.info("== START Count PENDING SEEDS ===");
        SeedContext context = SeedContext.builder(realm).build();
        
        // Use SeedDiscoveryService to discover and resolve latest versions
        Map<String, SeedPackDescriptor> latestByPack = seedDiscoveryService.discoverLatestApplicable(context, null);
        
        int count = 0;
        for (SeedPackDescriptor d : latestByPack.values()) {
            SeedPackManifest m = d.getManifest();
            boolean any = false;
            for (SeedPackManifest.Dataset ds : m.getDatasets()) {
                String checksum = seedDiscoveryService.calculateChecksum(d, ds);
                if (seedRegistry.shouldApply(context, m, ds, checksum)) {
                    any = true;
                    break;
                }
            }
            if (any) count++;
        }
        Log.infof("== END Count PENDING SEEDS Result: %d===", count);
        return count;
    }
}
