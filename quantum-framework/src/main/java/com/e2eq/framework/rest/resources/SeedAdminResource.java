package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.service.seed.SeedContext;
import com.e2eq.framework.service.seed.SeedCollectionResolver;
import com.e2eq.framework.service.seed.SeedPackDescriptor;
import com.e2eq.framework.service.seed.SeedPackManifest;
import com.e2eq.framework.service.seed.SeedPackRef;
import com.e2eq.framework.service.seed.SeedLoaderService;
import com.e2eq.framework.service.seed.SeedDiscoveryService;
import com.e2eq.framework.service.seed.SeedVerificationService;
import com.e2eq.framework.service.seed.SeedRegistry;
import com.e2eq.framework.service.seed.MorphiaSeedRegistry;
import com.e2eq.framework.service.seed.SeedRegistryEntry;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
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
@Tag(name = "seeds-admin", description = "Inspect, manage, and apply seed packs and archetypes")
public class SeedAdminResource {

    @Inject
    SeedLoaderService seedLoaderService;

    @Inject
    SeedDiscoveryService seedDiscoveryService;

    @Inject
    SeedRegistry seedRegistry;

    @Inject
    SeedVerificationService seedVerificationService;


    // ----- Endpoints -----

    @GET
    @Path("/pending/{realm}")
    @Operation(summary = "List pending seed packs", description = "Returns seed packs that have new or updated datasets not yet applied to the given realm.")
    @APIResponse(responseCode = "200", description = "Pending seed packs",
            content = @Content(schema = @Schema(implementation = PendingSeedPack[].class)))
    public List<PendingSeedPack> listPending(@PathParam("realm") String realm,
                                             @QueryParam("filter") String filterCsv) {
        SeedContext context = buildSeedContext(realm);
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
    @Operation(summary = "Seed application history", description = "Returns the history of seed pack applications for the given realm.")
    @APIResponse(responseCode = "200", description = "Seed history entries",
            content = @Content(schema = @Schema(implementation = HistoryEntry[].class)))
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
            e.records = entry.getRecords();
            e.appliedAt = entry.getAppliedAt() != null ? entry.getAppliedAt().toString() : null;
            result.add(e);
        }
        return result;
    }

    @GET
    @Path("/verify/{realm}")
    public SeedVerificationService.VerifyResult verifyAll(@PathParam("realm") String realm,
                                                          @QueryParam("filter") String filterCsv) {
        return seedVerificationService.verifyLatestApplicable(buildSeedContext(realm), filterCsv);
    }

    @GET
    @Path("/{realm}/{seedPack}/verify")
    public SeedVerificationService.VerifyResult verifyOne(@PathParam("realm") String realm,
                                                          @PathParam("seedPack") String seedPack) {
        return seedVerificationService.verifyOne(buildSeedContext(realm), seedPack);
    }

    @POST
    @Path("/apply/{realm}")
    @Operation(summary = "Apply all pending seed packs", description = "Discovers and applies all pending seed packs for the given realm.")
    @APIResponse(responseCode = "200", description = "Names of applied seed packs",
            content = @Content(schema = @Schema(implementation = ApplyResult.class)))
    public ApplyResult applyAll(@PathParam("realm") String realm,
                                @QueryParam("filter") String filterCsv) {
        SeedContext context = buildSeedContext(realm);

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

    @GET
    @Path("/archetypes/{realm}")
    @Operation(summary = "List available seed archetypes", description = "Returns archetypes defined in seed pack manifests that are applicable to the given realm.")
    @APIResponse(responseCode = "200", description = "Available archetypes",
            content = @Content(schema = @Schema(implementation = ArchetypeInfo[].class)))
    public List<ArchetypeInfo> listArchetypes(@PathParam("realm") String realm) {
        SeedContext context = buildSeedContext(realm);
        List<SeedPackDescriptor> descriptors;
        try {
            descriptors = seedDiscoveryService.discoverSeedPacks(context);
        } catch (IOException e) {
            throw new InternalServerErrorException("Failed to discover seed packs: " + e.getMessage());
        }

        descriptors = seedDiscoveryService.filterByScope(descriptors, context);
        List<ArchetypeInfo> archetypes = new ArrayList<>();
        for (SeedPackDescriptor d : descriptors) {
            for (SeedPackManifest.Archetype a : d.getManifest().getArchetypes()) {
                ArchetypeInfo info = new ArchetypeInfo();
                info.name = a.getName();
                info.seedPack = d.getManifest().getSeedPack();
                info.version = d.getManifest().getVersion();
                info.includes = a.getIncludeRefs().stream()
                        .map(SeedPackRef::getName)
                        .toList();
                archetypes.add(info);
            }
        }
        archetypes.sort(Comparator.comparing(a -> a.name));
        return archetypes;
    }

    @POST
    @Path("/archetypes/{realm}/{archetypeName}/apply")
    @Operation(summary = "Apply a seed archetype", description = "Applies the named archetype, seeding all packs it includes into the given realm.")
    @APIResponse(responseCode = "200", description = "Applied archetype name",
            content = @Content(schema = @Schema(implementation = ApplyResult.class)))
    public ApplyResult applyArchetype(@PathParam("realm") String realm,
                                      @PathParam("archetypeName") String archetypeName) {
        SeedContext context = buildSeedContext(realm);
        seedLoaderService.applyArchetype(context, archetypeName);
        ApplyResult result = new ApplyResult();
        result.applied = List.of(archetypeName);
        return result;
    }

    @POST
    @Path("/{realm}/{seedPack}/apply")
    @Operation(summary = "Apply a single seed pack", description = "Applies the latest version of the specified seed pack to the given realm.")
    @APIResponse(responseCode = "200", description = "Applied seed pack name",
            content = @Content(schema = @Schema(implementation = ApplyResult.class)))
    @APIResponse(responseCode = "404", description = "Seed pack not found or not applicable to this realm")
    public ApplyResult applyOne(@PathParam("realm") String realm,
                                @PathParam("seedPack") String seedPack) {
        SeedContext context = buildSeedContext(realm);

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

    /**
     * Builds a SeedContext for the given realm, populating DataDomain fields from the current
     * security context. This ensures that seed records receive proper tenantId, orgRefName,
     * accountNum, and ownerId values required by validation.
     *
     * @param realm the target realm for seeding
     * @return a fully populated SeedContext
     */
    private SeedContext buildSeedContext(String realm) {
        SeedContext.Builder builder = SeedContext.builder(realm);

        // Extract DataDomain from security context if available
        Optional<PrincipalContext> principalContext = SecurityContext.getPrincipalContext();
        if (principalContext.isPresent()) {
            PrincipalContext pc = principalContext.get();
            DataDomain dd = pc.getDataDomain();
            if (dd != null) {
                builder.tenantId(dd.getTenantId())
                       .orgRefName(dd.getOrgRefName())
                       .accountId(dd.getAccountNum())
                       .ownerId(pc.getUserId());
            }
        } else {
            Log.warnf("SeedAdminResource: No security context available for realm %s. " +
                      "Seed records may fail validation if DataDomain fields are required.", realm);
        }

        return builder.build();
    }

    private PendingSeedPack computePendingForDescriptor(SeedRegistry registry,
                                                        SeedContext context,
                                                        SeedPackDescriptor d) {
        SeedPackManifest m = d.getManifest();
        boolean anyPending = false;
        List<PendingDataset> datasets = new ArrayList<>();
        for (SeedPackManifest.Dataset ds : m.getDatasets()) {
            SeedCollectionResolver.ensureCollectionSet(ds);
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

    @Schema(description = "A seed pack with datasets pending application")
    public static final class PendingSeedPack {
        @Schema(description = "Composite identifier: seedPack@version", example = "qa-test-locations@1.0.0")
        public String seedId;
        @Schema(description = "Seed pack name", example = "qa-test-locations")
        public String seedPack;
        @Schema(description = "Seed pack version", example = "1.0.0")
        public String version;
        @Schema(description = "Datasets within this pack that are pending")
        public List<PendingDataset> datasets;
    }

    @Schema(description = "A single dataset file within a pending seed pack")
    public static final class PendingDataset {
        @Schema(description = "Target MongoDB collection", example = "location")
        public String collection;
        @Schema(description = "Dataset file name", example = "test-locations.jsonl")
        public String file;
        @Schema(description = "SHA-256 checksum of the dataset file")
        public String checksum;
        public PendingDataset() {}
        public PendingDataset(String collection, String file, String checksum) {
            this.collection = collection;
            this.file = file;
            this.checksum = checksum;
        }
    }

    @Schema(description = "Record of a previously applied seed dataset")
    public static final class HistoryEntry {
        @Schema(description = "Seed pack name", example = "qa-test-locations")
        public String seedPack;
        @Schema(description = "Seed pack version", example = "1.0.0")
        public String version;
        @Schema(description = "Dataset file name", example = "test-locations.jsonl")
        public String dataset;
        @Schema(description = "Checksum at time of application")
        public String checksum;
        @Schema(description = "Number of records applied")
        public Integer records;
        @Schema(description = "ISO-8601 timestamp when the dataset was applied")
        public String appliedAt;
    }

    @Schema(description = "Result of a seed apply operation")
    public static final class ApplyResult {
        @Schema(description = "Names of seed packs or archetypes that were applied")
        public List<String> applied;
    }

    @Schema(description = "Metadata for a seed archetype defined in a seed pack manifest")
    public static final class ArchetypeInfo {
        @Schema(description = "Archetype name as defined in the manifest", example = "qa-full-harness")
        public String name;
        @Schema(description = "Seed pack that defines this archetype", example = "qa-harness")
        public String seedPack;
        @Schema(description = "Seed pack version", example = "1.0.0")
        public String version;
        @Schema(description = "Ordered list of seed pack names included in this archetype")
        public List<String> includes;
    }
}
