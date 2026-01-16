package com.e2eq.framework.rest.resources;

import com.e2eq.framework.service.seed.SeedContext;
import com.e2eq.framework.service.seed.SeedPackDescriptor;
import com.e2eq.framework.service.seed.SeedPackManifest;
import com.e2eq.framework.service.seed.SeedPackRef;
import com.e2eq.framework.service.seed.SeedLoaderService;
import com.e2eq.framework.service.seed.SeedDiscoveryService;
import com.e2eq.framework.service.seed.SeedRegistry;
import com.e2eq.framework.service.seed.MorphiaSeedRegistry;
import com.e2eq.framework.service.seed.SeedRegistryEntry;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin endpoints to inspect and apply seed packs on demand.
 * These endpoints operate on a specific realm (tenant database).
 */
@Path("/admin/seeds")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "system"})
@ApplicationScoped
public class SeedAdminResource {

    @Inject
    SeedLoaderService seedLoaderService;

    @Inject
    SeedDiscoveryService seedDiscoveryService;

    @Inject
    SeedRegistry seedRegistry;


    // ----- Endpoints -----

    @GET
    @Path("/pending/{realm}")
    public List<PendingSeedPack> listPending(@PathParam("realm") String realm,
                                             @QueryParam("filter") String filterCsv) {
        SeedContext context = SeedContext.builder(realm).build();
        Map<String, SeedPackDescriptor> latestByPack;
        try {
            latestByPack = seedDiscoveryService.discoverLatestApplicable(context, filterCsv);
        } catch (IOException e) {
            throw new InternalServerErrorException("Failed to discover seed packs: " + e.getMessage());
        }

        if (latestByPack.isEmpty()) {
            Log.infof("No seed packs found in realm %s", realm);
            return List.of();
        }

        List<PendingSeedPack> pending = new ArrayList<>();
        for (SeedPackDescriptor d : latestByPack.values()) {
            PendingSeedPack p = computePendingForDescriptor(seedRegistry, context, d);
            if (p != null) pending.add(p);
        }
        // stable order by seedId
        pending.sort(Comparator.comparing(p -> p.seedId));
        return pending;
    }

    @GET
    @Path("/history/{realm}")
    public List<HistoryEntry> history(@PathParam("realm") String realm) {
        if (!(seedRegistry instanceof MorphiaSeedRegistry morphiaRegistry)) {
            throw new InternalServerErrorException("SeedRegistry is not MorphiaSeedRegistry");
        }
        List<SeedRegistryEntry> entries = morphiaRegistry.getHistory(realm);
        List<HistoryEntry> result = new ArrayList<>();
        for (SeedRegistryEntry entry : entries) {
            HistoryEntry e = new HistoryEntry();
            e.seedPack = entry.getSeedPack();
            e.version = entry.getVersion();
            e.dataset = entry.getDataset();
            e.checksum = entry.getChecksum();
            e.records = entry.getRecordsApplied();
            e.appliedAt = entry.getAppliedAt() != null ? entry.getAppliedAt().toString() : null;
            result.add(e);
        }
        return result;
    }

    @POST
    @Path("/apply/{realm}")
    public ApplyResult applyAll(@PathParam("realm") String realm,
                                @QueryParam("filter") String filterCsv) {
        SeedContext context = SeedContext.builder(realm).build();

        Map<String, SeedPackDescriptor> latestByPack;
        try {
            latestByPack = seedDiscoveryService.discoverLatestApplicable(context, filterCsv);
        } catch (IOException e) {
            throw new InternalServerErrorException("Failed to discover seed packs: " + e.getMessage());
        }

        List<SeedPackRef> refs = latestByPack.values().stream()
                .map(d -> SeedPackRef.exact(d.getManifest().getSeedPack(), d.getManifest().getVersion()))
                .collect(Collectors.toList());
        seedLoaderService.applySeeds(context, refs);
        ApplyResult result = new ApplyResult();
        result.applied = latestByPack.keySet().stream().sorted().toList();
        return result;
    }

    @POST
    @Path("/{realm}/{seedPack}/apply")
    public ApplyResult applyOne(@PathParam("realm") String realm,
                                @PathParam("seedPack") String seedPack) {
        SeedContext context = SeedContext.builder(realm).build();

        // Discover and find latest version of the given pack
        List<SeedPackDescriptor> descriptors;
        try {
            descriptors = seedDiscoveryService.discoverSeedPacks(context);
        } catch (IOException e) {
            throw new NotFoundException("Failed to discover seed packs: " + e.getMessage());
        }

        descriptors = seedDiscoveryService.filterByScope(descriptors, context);
        SeedPackDescriptor latest = descriptors.stream()
                .filter(d -> seedPack.equals(d.getManifest().getSeedPack()))
                .max(Comparator.comparing(d -> {
                    org.semver4j.Semver v = org.semver4j.Semver.parse(d.getManifest().getVersion());
                    return v != null ? v : org.semver4j.Semver.parse("0.0.0");
                }))
                .orElseThrow(() -> new NotFoundException("Seed pack not found or not applicable: " + seedPack));
        seedLoaderService.applySeeds(context, List.of(SeedPackRef.exact(seedPack, latest.getManifest().getVersion())));
        ApplyResult result = new ApplyResult();
        result.applied = List.of(seedPack);
        return result;
    }

    // ----- Helpers -----

    private PendingSeedPack computePendingForDescriptor(SeedRegistry registry,
                                                        SeedContext context,
                                                        SeedPackDescriptor d) {
        SeedPackManifest m = d.getManifest();
        boolean anyPending = false;
        List<PendingDataset> datasets = new ArrayList<>();
        for (SeedPackManifest.Dataset ds : m.getDatasets()) {
            String checksum = seedDiscoveryService.calculateChecksum(d, ds);
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
