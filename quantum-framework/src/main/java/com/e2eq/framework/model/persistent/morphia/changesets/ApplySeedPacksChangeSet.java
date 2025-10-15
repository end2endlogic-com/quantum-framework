package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;
import com.e2eq.framework.service.seed.*;
import com.mongodb.client.MongoClient;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * ChangeSet that discovers and applies Seed Packs for the current realm.
 * It re-executes automatically when any seed pack manifest or dataset changes
 * by exposing a version derived from a combined fingerprint of all seed inputs.
 */
// Deprecated: No longer a CDI bean. Seeding is handled by SeedStartupRunner.
public class ApplySeedPacksChangeSet extends ChangeSetBase implements ChangeSetBean {

    @Inject
    MongoClient mongoClient;

    // Optional root directory for seed packs. Tests can set this to src/test/resources/seed-packs
    @ConfigProperty(name = "quantum.seed.root", defaultValue = "")
    Optional<String> seedRootConfig;

    // Optional comma-separated list of seed pack names to apply. When present, only these packs are applied.
    @ConfigProperty(name = "quantum.seed.apply.filter", defaultValue = "")
    Optional<String> seedApplyFilter;

    private static final String DEFAULT_TEST_SEED_ROOT = "src/test/resources/seed-packs";

    @Override
    public String getId() {
        return "SEED-APPLY-0001";
    }

    @Override
    public int getChangeSetVersion() {
        try {
            Path root = resolveSeedRoot();
            return computeFingerprintVersion(root);
        } catch (Exception e) {
            // If fingerprinting fails, return a fixed version so migrations don't crash
            Log.warn("Failed computing seed packs fingerprint; defaulting version to 1", e);
            return 1;
        }
    }

    @Override
    public int getDbFromVersionInt() { return 100; }

    @Override
    public int getDbToVersionInt() { return 100; }

    @Override
    public String getDbFromVersion() { return "1.0.0"; }

    @Override
    public String getDbToVersion() { return "1.0.0"; }

    @Override
    public int getPriority() { return 50; }

    @Override
    public String getAuthor() { return "Quantum Framework"; }

    @Override
    public String getName() { return "Apply Seed Packs"; }

    @Override
    public String getDescription() { return "Discovers and applies seed packs; re-runs when packs change"; }

    @Override
    public String getScope() { return "ALL"; }

    @Override
    public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) throws Exception {
        Path root = resolveSeedRoot();
        emitter.emit("Seed packs root: " + root.toAbsolutePath());
        Log.infof("Applying seed packs from %s to realm %s", root.toAbsolutePath(), session.getDatabase().getName());

        SeedLoader loader = SeedLoader.builder()
                .addSeedSource(new FileSeedSource("files", root))
                .seedRepository(new MongoSeedRepository(mongoClient))
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .build();

        // Discover all seed packs and apply all latest versions
        SeedContext context = SeedContext.builder(session.getDatabase().getName()).build();

        // Resolve and apply all available packs
        List<SeedPackDescriptor> descriptors = new FileSeedSource("files", root).loadSeedPacks(context);
        if (descriptors.isEmpty()) {
            emitter.emit("No seed packs found under: " + root);
            return;
        }
        // Optional filtering by configured pack names
        Set<String> allowed = parseAllowedPacks();
        if (!allowed.isEmpty()) {
            descriptors.removeIf(d -> !allowed.contains(d.getManifest().getSeedPack()));
            if (descriptors.isEmpty()) {
                emitter.emit("No seed packs matched filter: " + allowed);
                return;
            }
        }
        // Build refs to latest version of each pack name
        Map<String, SeedPackDescriptor> latestByName = new HashMap<>();
        for (SeedPackDescriptor d : descriptors) {
            String name = d.getManifest().getSeedPack();
            latestByName.merge(name, d, (a, b) -> compareSemver(a.getManifest().getVersion(), b.getManifest().getVersion()) >= 0 ? a : b);
        }
        List<SeedPackRef> refs = new ArrayList<>();
        latestByName.values().forEach(d -> refs.add(SeedPackRef.exact(d.getManifest().getSeedPack(), d.getManifest().getVersion())));
        loader.apply(refs, context);
        emitter.emit("Applied seed packs: " + latestByName.keySet());
    }

    private Path resolveSeedRoot() {
        if (seedRootConfig.isPresent() && !seedRootConfig.get().isBlank()) {
            return Path.of(seedRootConfig.get());
        }
        // Fallback for tests: relative to repo root
        return Path.of(DEFAULT_TEST_SEED_ROOT);
    }

    private int computeFingerprintVersion(Path root) throws IOException, NoSuchAlgorithmException {
        if (!Files.exists(root)) return 1;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // Include manifest paths and contents, plus dataset file contents
        FileSeedSource source = new FileSeedSource("files", root);
        SeedContext context = SeedContext.builder("fingerprint").build();
        List<SeedPackDescriptor> descriptors = source.loadSeedPacks(context);
        // Sort for stability
        descriptors.sort(Comparator.comparing(d -> d.getManifest().getSeedPack() + ":" + d.getManifest().getVersion()));
        for (SeedPackDescriptor d : descriptors) {
            SeedPackManifest m = d.getManifest();
            update(digest, (m.getSeedPack() + "@" + m.getVersion()).getBytes(StandardCharsets.UTF_8));
            m.getDatasets().stream()
                    .sorted(Comparator.comparing(SeedPackManifest.Dataset::getFile))
                    .forEach(dataset -> {
                        update(digest, dataset.getCollection().getBytes(StandardCharsets.UTF_8));
                        update(digest, dataset.getFile().getBytes(StandardCharsets.UTF_8));
                        try (InputStream in = source.openDataset(d, dataset.getFile())) {
                            byte[] bytes = in.readAllBytes();
                            update(digest, bytes);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        byte[] hash = digest.digest();
        // Fold to 32-bit int for changeSetVersion
        int version = 0;
        for (int i = 0; i < 4 && i < hash.length; i++) {
            version = (version << 8) | (hash[i] & 0xFF);
        }
        // Ensure positive
        return version == 0 ? 1 : Math.abs(version);
    }

    private static void update(MessageDigest digest, byte[] bytes) {
        digest.update(bytes);
    }

    private static int compareSemver(String a, String b) {
        // Simple semantic version compare: split by dots lexicographically with integer compare
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

    private Set<String> parseAllowedPacks() {
        if (seedApplyFilter.isEmpty()) return Collections.emptySet();
        String raw = seedApplyFilter.get().trim();
        if (raw.isEmpty()) return Collections.emptySet();
        Set<String> set = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) set.add(name);
        }
        return set;
    }
}
