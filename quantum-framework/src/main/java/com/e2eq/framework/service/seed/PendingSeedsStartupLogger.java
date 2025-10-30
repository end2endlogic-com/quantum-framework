package com.e2eq.framework.service.seed;

import com.mongodb.client.MongoClient;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.*;

import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.service.seed.SeedPathResolver;

/**
 * Logs a summary of pending seed packs at application startup for key realms.
 */
@Startup
@ApplicationScoped
public class PendingSeedsStartupLogger {

    @Inject
    MongoClient mongoClient;

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
        java.nio.file.Path root = SeedPathResolver.resolveSeedRoot();
        FileSeedSource source = new FileSeedSource("files", root);
        SeedContext context = SeedContext.builder(realm).build();
        MongoSeedRegistry registry = new MongoSeedRegistry(mongoClient);
        List<SeedPackDescriptor> descriptors = source.loadSeedPacks(context);
        // Filter by scope applicability (gated by feature flag)
        descriptors.removeIf(d -> !com.e2eq.framework.service.seed.ScopeMatcher.isApplicable(d, context));
        Map<String, SeedPackDescriptor> latestByPack = new LinkedHashMap<>();
        for (SeedPackDescriptor d : descriptors) {
            String name = d.getManifest().getSeedPack();
            latestByPack.merge(name, d, (a, b) -> compareSemver(a.getManifest().getVersion(), b.getManifest().getVersion()) >= 0 ? a : b);
        }
        int count = 0;
        for (SeedPackDescriptor d : latestByPack.values()) {
            SeedPackManifest m = d.getManifest();
            boolean any = false;
            for (SeedPackManifest.Dataset ds : m.getDatasets()) {
                String checksum = ""; // minimal: presence indicates change; content checksum would require IO
                // Use repository checksum method from SeedAdminResource approach for accuracy
                try (var in = source.openDataset(d, ds.getFile())) {
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] bytes = in.readAllBytes();
                    checksum = java.util.HexFormat.of().formatHex(digest.digest(bytes));
                } catch (Exception e) {
                    // If checksum fails, assume pending as a safe default
                    checksum = "";
                }
                if (registry.shouldApply(context, m, ds, checksum)) {
                    any = true; break;
                }
            }
            if (any) count++;
        }
        return count;
    }


    private static int compareSemver(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            int ai = i < as.length ? parseInt(as[i]) : 0;
            int bi = i < bs.length ? parseInt(bs[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
