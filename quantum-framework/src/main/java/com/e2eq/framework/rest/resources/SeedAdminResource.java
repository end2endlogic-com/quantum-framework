package com.e2eq.framework.rest.resources;

import com.e2eq.framework.service.seed.FileSeedSource;
import com.e2eq.framework.service.seed.MongoSeedRegistry;
import com.e2eq.framework.service.seed.SeedContext;
import com.e2eq.framework.service.seed.SeedLoader;
import com.e2eq.framework.service.seed.SeedPackDescriptor;
import com.e2eq.framework.service.seed.SeedPackManifest;
import com.e2eq.framework.service.seed.SeedPackRef;
import com.e2eq.framework.service.seed.MorphiaSeedRepository;
import com.mongodb.client.MongoClient;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import com.e2eq.framework.service.seed.SeedPathResolver;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin endpoints to inspect and apply seed packs on demand.
 * These endpoints operate on a specific realm (tenant database).
 */
@Path("/admin/seeds")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin"})
@ApplicationScoped
public class SeedAdminResource {

    @Inject
    MongoClient mongoClient;

    @Inject
    MorphiaSeedRepository morphiaSeedRepository;


    // ----- Endpoints -----

    @GET
    @Path("/pending/{realm}")
    public List<PendingSeedPack> listPending(@PathParam("realm") String realm,
                                             @QueryParam("filter") String filterCsv) {
        java.nio.file.Path root = SeedPathResolver.resolveSeedRoot();
        SeedContext context = SeedContext.builder(realm).build();
        FileSeedSource source = new FileSeedSource("files", root);
        MongoSeedRegistry registry = new MongoSeedRegistry(mongoClient);
        List<SeedPackDescriptor> descriptors;
        try {
            descriptors = source.loadSeedPacks(context);
        } catch (IOException e) {
            throw new InternalServerErrorException("Failed to load seed packs: " + e.getMessage());
        }
        if (descriptors.isEmpty()) {
           Log.infof("No seed packs found in realm %s ", realm);
            return List.of();
        }
        Set<String> allowed = parseFilter(filterCsv);
        if (!allowed.isEmpty()) {
            descriptors.removeIf(d -> !allowed.contains(d.getManifest().getSeedPack()));
        }
        // Filter by scope applicability (gated by feature flag)
        descriptors.removeIf(d -> !com.e2eq.framework.service.seed.ScopeMatcher.isApplicable(d, context));
        Map<String, SeedPackDescriptor> latestByPack = latestByPack(descriptors);
        List<PendingSeedPack> pending = new ArrayList<>();
        for (SeedPackDescriptor d : latestByPack.values()) {
            PendingSeedPack p = computePendingForDescriptor(source, registry, context, d);
            if (p != null) pending.add(p);
        }
        // stable order by seedId
        pending.sort(Comparator.comparing(p -> p.seedId));
        return pending;
    }

    @GET
    @Path("/history/{realm}")
    public List<HistoryEntry> history(@PathParam("realm") String realm) {
        // Expose raw registry entries; a dedicated projection could be added later.
        var db = mongoClient.getDatabase(realm);
        var col = db.getCollection("_seed_registry");
        List<HistoryEntry> entries = new ArrayList<>();
        for (var doc : col.find()) {
            HistoryEntry e = new HistoryEntry();
            e.seedPack = Objects.toString(doc.get("seedPack"), null);
            e.version = Objects.toString(doc.get("version"), null);
            e.dataset = Objects.toString(doc.get("dataset"), null);
            e.checksum = Objects.toString(doc.get("checksum"), null);
            e.records = doc.get("records") instanceof Number n ? n.intValue() : null;
            e.appliedAt = Objects.toString(doc.get("appliedAt"), null);
            entries.add(e);
        }
        // Latest first (rough ordering by appliedAt desc then seedPack)
        entries.sort(Comparator.comparing((HistoryEntry e) -> Objects.toString(e.appliedAt, "")).reversed()
                .thenComparing(e -> e.seedPack, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(e -> e.dataset, Comparator.nullsLast(Comparator.naturalOrder())));
        return entries;
    }

    @POST
    @Path("/apply/{realm}")
    public ApplyResult applyAll(@PathParam("realm") String realm,
                                @QueryParam("filter") String filterCsv) {
        java.nio.file.Path root = SeedPathResolver.resolveSeedRoot();
        SeedLoader loader = SeedLoader.builder()
                .addSeedSource(new FileSeedSource("files", root))
                .seedRepository(morphiaSeedRepository)
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .build();
        SeedContext context = SeedContext.builder(realm).build();

        // Discover latest by pack and optionally filter
        FileSeedSource source = new FileSeedSource("files", root);
        List<SeedPackDescriptor> descriptors;
        try {
            descriptors = source.loadSeedPacks(context);
        } catch (IOException e) {
            throw new InternalServerErrorException("Failed to load seed packs: " + e.getMessage());
        }
        Set<String> allowed = parseFilter(filterCsv);
        if (!allowed.isEmpty()) {
            descriptors.removeIf(d -> !allowed.contains(d.getManifest().getSeedPack()));
        }
        // Filter by scope applicability (gated by feature flag)
        descriptors.removeIf(d -> !com.e2eq.framework.service.seed.ScopeMatcher.isApplicable(d, context));
        Map<String, SeedPackDescriptor> latestByPack = latestByPack(descriptors);
        List<SeedPackRef> refs = latestByPack.values().stream()
                .map(d -> SeedPackRef.exact(d.getManifest().getSeedPack(), d.getManifest().getVersion()))
                .collect(Collectors.toList());
        loader.apply(refs, context);
        ApplyResult result = new ApplyResult();
        result.applied = latestByPack.keySet().stream().sorted().toList();
        return result;
    }

    @POST
    @Path("/{realm}/{seedPack}/apply")
    public ApplyResult applyOne(@PathParam("realm") String realm,
                                @PathParam("seedPack") String seedPack) {
        java.nio.file.Path root = com.e2eq.framework.service.seed.SeedPathResolver.resolveSeedRoot();
        SeedLoader loader = SeedLoader.builder()
                .addSeedSource(new FileSeedSource("files", root))
                .seedRepository(morphiaSeedRepository)
                .seedRegistry(new MongoSeedRegistry(mongoClient))
                .build();
        SeedContext context = SeedContext.builder(realm).build();

        // Find latest version of the given pack
        FileSeedSource source = new FileSeedSource("files", root);
        List<SeedPackDescriptor> descriptors;
        try {
            descriptors = source.loadSeedPacks(context);
        } catch (IOException e) {
            throw new NotFoundException("Failed to load seed packs: " + e.getMessage());
        }
        // Apply scope filtering first
        descriptors.removeIf(d -> !com.e2eq.framework.service.seed.ScopeMatcher.isApplicable(d, context));
        SeedPackDescriptor latest = descriptors.stream()
                .filter(d -> seedPack.equals(d.getManifest().getSeedPack()))
                .max(Comparator.comparing(d -> d.getManifest().getVersion(), SeedAdminResource::compareSemver))
                .orElseThrow(() -> new NotFoundException("Seed pack not found or not applicable: " + seedPack));
        loader.apply(List.of(SeedPackRef.exact(seedPack, latest.getManifest().getVersion())), context);
        ApplyResult result = new ApplyResult();
        result.applied = List.of(seedPack);
        return result;
    }

    // ----- Helpers -----

    private PendingSeedPack computePendingForDescriptor(FileSeedSource source,
                                                        MongoSeedRegistry registry,
                                                        SeedContext context,
                                                        SeedPackDescriptor d) {
        SeedPackManifest m = d.getManifest();
        boolean anyPending = false;
        List<PendingDataset> datasets = new ArrayList<>();
        for (SeedPackManifest.Dataset ds : m.getDatasets()) {
            String checksum = checksumOf(source, d, ds);
            boolean should = registry.shouldApply(context, m, ds, checksum);
            if (should) {
                anyPending = true;
                datasets.add(new PendingDataset(ds.getCollection(), ds.getFile(), checksum));
            }
        }
        if (!anyPending) return null;
        PendingSeedPack p = new PendingSeedPack();
        p.seedId = m.getSeedPack() + "@" + m.getVersion();
        p.seedPack = m.getSeedPack();
        p.version = m.getVersion();
        p.datasets = datasets;
        return p;
    }

    private static Map<String, SeedPackDescriptor> latestByPack(List<SeedPackDescriptor> descriptors) {
        Map<String, SeedPackDescriptor> latestByName = new HashMap<>();
        for (SeedPackDescriptor d : descriptors) {
            String name = d.getManifest().getSeedPack();
            latestByName.merge(name, d, (a, b) -> compareSemver(a.getManifest().getVersion(), b.getManifest().getVersion()) >= 0 ? a : b);
        }
        return latestByName;
    }


    private static Set<String> parseFilter(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        Set<String> set = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) set.add(name);
        }
        return set;
    }

    public static int compareSemver (String a, String b) {
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

    private static String checksumOf(FileSeedSource source, SeedPackDescriptor descriptor, SeedPackManifest.Dataset dataset) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = source.openDataset(descriptor, dataset.getFile())) {
                byte[] bytes = in.readAllBytes();
                return java.util.HexFormat.of().formatHex(digest.digest(bytes));
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.warn("Failed to compute dataset checksum for " + dataset.getFile() + ": " + e.getMessage());
            return "";
        }
    }

    private static void update(MessageDigest digest, byte[] bytes) {
        digest.update(bytes);
    }

    // ----- DTOs -----
    public static final class PendingSeedPack {
        public String seedId;
        public String seedPack;
        public String version;
        public List<PendingDataset> datasets;
    }
    public static final class PendingDataset {
        public String collection;
        public String file;
        public String checksum;
        public PendingDataset() {}
        public PendingDataset(String collection, String file, String checksum) {
            this.collection = collection;
            this.file = file;
            this.checksum = checksum;
        }
    }
    public static final class HistoryEntry {
        public String seedPack;
        public String version;
        public String dataset;
        public String checksum;
        public Integer records;
        public String appliedAt;
    }
    public static final class ApplyResult {
        public List<String> applied;
    }
}
