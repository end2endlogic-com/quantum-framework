package com.e2eq.framework.service.seed;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
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

    private static final String DEFAULT_TEST_SEED_ROOT = "src/test/resources/seed-packs";

    @Inject
    MongoClient mongoClient;

    @Inject
    EnvConfigUtils envConfigUtils;

    @ConfigProperty(name = "quantum.seeds.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "quantum.seed.root", defaultValue = "")
    Optional<String> seedRootConfig;

    @ConfigProperty(name = "quantum.seeds.apply.on-startup", defaultValue = "true")
    boolean applyOnStartup;

    @PostConstruct
    void onStart() {
        if (!enabled || !applyOnStartup) {
            Log.info("SeedStartupRunner disabled by configuration (quantum.seeds.enabled / quantum.seeds.apply.on-startup)");
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
                applySeedsIfNeeded(realm);
            } catch (Exception e) {
                Log.warnf("SeedStartupRunner: failed applying seeds for realm %s: %s", realm, e.getMessage());
            }
        }
    }

    private void applySeedsIfNeeded(String realm) throws Exception {
        DistributedLock lock = getSeedLock(realm);
        lock.acquire();
        try {
            Path root = resolveSeedRoot();
            FileSeedSource source = new FileSeedSource("files", root);
            SeedContext context = SeedContext.builder(realm).build();
            // Discover packs and select latest per name
            List<SeedPackDescriptor> descriptors = source.loadSeedPacks(context);
            if (descriptors.isEmpty()) {
                Log.infof("SeedStartupRunner: no seed packs found under %s for realm %s", root.toAbsolutePath(), realm);
                return;
            }
            Map<String, SeedPackDescriptor> latestByName = new LinkedHashMap<>();
            for (SeedPackDescriptor d : descriptors) {
                String name = d.getManifest().getSeedPack();
                latestByName.merge(name, d, (a, b) -> compareSemver(a.getManifest().getVersion(), b.getManifest().getVersion()) >= 0 ? a : b);
            }
            // Apply via SeedLoader. MongoSeedRegistry ensures idempotency/skip per dataset checksum.
            SeedLoader loader = SeedLoader.builder()
                    .addSeedSource(source)
                    .seedRepository(new MongoSeedRepository(mongoClient))
                    .seedRegistry(new MongoSeedRegistry(mongoClient))
                    .build();
            List<SeedPackRef> refs = new ArrayList<>();
            latestByName.values().forEach(d -> refs.add(SeedPackRef.exact(d.getManifest().getSeedPack(), d.getManifest().getVersion())));
            if (refs.isEmpty()) {
                Log.infof("SeedStartupRunner: no applicable seed packs for realm %s", realm);
                return;
            }
            Log.infof("SeedStartupRunner: applying %d seed pack(s) to realm %s from %s", refs.size(), realm, root.toAbsolutePath());
            loader.apply(refs, context);
        } finally {
            lock.release();
        }
    }

    private Path resolveSeedRoot() {
        if (seedRootConfig.isPresent() && !seedRootConfig.get().isBlank()) {
            return Path.of(seedRootConfig.get());
        }
        return Path.of(DEFAULT_TEST_SEED_ROOT);
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
    private static int parseInt(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }

    private DistributedLock getSeedLock(String realm) {
        MongoCollection<Document> collection = mongoClient
                .getDatabase("sherlock")
                .getCollection("locks");
        Sherlock sherlock = MongoSherlock.create(collection);
        return sherlock.createLock(String.format("seed-lock-%s", realm));
    }
}
