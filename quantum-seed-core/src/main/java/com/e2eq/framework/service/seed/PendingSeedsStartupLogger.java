package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.*;

import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.system.config.SystemRealmOwnership;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Logs a summary of pending seed packs at application startup for key realms.
 *
 * <p>The report opens each realm's seed registry, so it creates and maps that realm's
 * datastore before the service can answer. It runs only when seeding is enabled
 * ({@code quantum.seed-pack.enabled}) and {@code quantum.seed-pack.pending-report.on-startup}
 * is {@code true} (the default). With it off, ask on demand:
 * {@code GET /admin/seeds/pending/{realm}}.</p>
 */
@ApplicationScoped
public class PendingSeedsStartupLogger {

    @Inject
    SeedDiscoveryService seedDiscoveryService;

    @Inject
    SeedRegistry seedRegistry;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    SystemRealmOwnership systemRealmOwnership;

    @ConfigProperty(name = "quantum.seed-pack.enabled", defaultValue = "true")
    boolean seedsEnabled;

    @ConfigProperty(name = "quantum.seed-pack.pending-report.on-startup", defaultValue = "true")
    boolean reportOnStartup;

    public void onStart() {
        if (!seedsEnabled || !reportOnStartup) {
            Log.infof("SeedPacks: startup pending-seed report off (quantum.seed-pack.enabled=%s, "
                + "quantum.seed-pack.pending-report.on-startup=%s); use GET /admin/seeds/pending/{realm}",
                seedsEnabled, reportOnStartup);
            return;
        }
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
        systemRealmOwnership.excludeSystemRealmIfNotOwned(realms, "PendingSeedsStartupLogger");
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
