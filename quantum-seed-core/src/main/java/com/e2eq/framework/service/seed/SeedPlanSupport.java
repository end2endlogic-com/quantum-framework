package com.e2eq.framework.service.seed;

import com.e2eq.framework.service.seed.SeedLoader.DatasetPayload;
import com.e2eq.framework.service.seed.SeedLoader.SeedResolution;
import com.e2eq.framework.service.seed.SeedPackManifest.Dataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.semver4j.Semver;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Exact artifact planning over the existing loader; does not own writes or durable runs. */
final class SeedPlanSupport {
    private final SeedLoader loader;
    private final ObjectMapper objectMapper;
    private final SeedConflictPolicy conflictPolicy;

    SeedPlanSupport(SeedLoader loader, ObjectMapper objectMapper, SeedConflictPolicy conflictPolicy) {
        this.loader = loader;
        this.objectMapper = objectMapper;
        this.conflictPolicy = conflictPolicy;
    }

    /** Resolve ranges and dependencies once, without writing to the repository or registry. */
    public SeedPlan plan(List<SeedPackRef> refs, SeedContext context) {
        return plan(refs, context, loader.resolveSeedPacks(context));
    }

    public SeedPlan planArchetype(String archetypeName, SeedContext context) {
        SeedResolution resolution = loader.resolveSeedPacks(context);
        SeedPackDescriptor owner = resolution.findArchetype(archetypeName)
                .orElseThrow(() -> new SeedLoadingException("Archetype " + archetypeName + " not found"));
        List<SeedPackRef> refs = new ArrayList<>(owner.getManifest().findArchetype(archetypeName)
                .orElseThrow().getIncludeRefs());
        refs.add(SeedPackRef.exact(owner.getManifest().getSeedPack(), owner.getManifest().getVersion()));
        return plan(refs, context, resolution);
    }

    private SeedPlan plan(List<SeedPackRef> refs, SeedContext context, SeedResolution resolution) {
        Map<String, SeedPackDescriptor> ordered = new LinkedHashMap<>();
        for (SeedPackRef ref : refs) collectPlan(resolution.resolve(ref), context, resolution,
                ordered, new LinkedHashSet<>());
        List<SeedPlan.Pack> packs = new ArrayList<>();
        for (SeedPackDescriptor descriptor : ordered.values()) {
            List<SeedPlan.Dataset> datasets = new ArrayList<>();
            for (Dataset dataset : loader.orderDatasetsByDependencies(descriptor)) {
                SeedCollectionResolver.ensureCollectionSet(dataset);
                loader.buildTransforms(dataset);
                datasets.add(new SeedPlan.Dataset(dataset.getFile(), loader.readDataset(descriptor, dataset).checksum()));
            }
            packs.add(new SeedPlan.Pack(descriptor.getManifest().getSeedPack(),
                    descriptor.getManifest().getVersion(), manifestHash(descriptor), datasets));
        }
        return new SeedPlan(1, contextHash(context), packs);
    }

    private void collectPlan(SeedPackDescriptor descriptor, SeedContext context, SeedResolution resolution,
                             Map<String, SeedPackDescriptor> ordered, Set<String> visiting) {
        descriptor.getManifest().validate();
        if (!visiting.add(descriptor.identity()))
            throw new SeedLoadingException("SEED_PLAN_CYCLE: " + descriptor.identity());
        if (!ScopeMatcher.isApplicable(descriptor, context))
            throw new SeedLoadingException("SEED_PLAN_SCOPE_MISMATCH: " + descriptor.identity());
        SeedPackDescriptor previous = ordered.get(descriptor.getManifest().getSeedPack());
        if (previous != null) {
            if (!previous.identity().equals(descriptor.identity()))
                throw new SeedLoadingException("SEED_PLAN_VERSION_CONFLICT: " + descriptor.identity());
        } else {
            for (SeedPackRef include : descriptor.getManifest().getIncludeRefs())
                collectPlan(resolution.resolve(include), context, resolution, ordered, visiting);
            ordered.put(descriptor.getManifest().getSeedPack(), descriptor);
        }
        visiting.remove(descriptor.identity());
    }

    /**
     * Validate every artifact before the first write, then execute those same parsed bytes.
     * Dependencies are already ordered and exact; ranges are never resolved again here.
     * The caller owns durable plan storage, exclusive execution and receipt reconciliation.
     */
    public void applyPlan(SeedPlan plan, SeedContext context) {
        if (!plan.contextSha256().equals(contextHash(context)))
            throw new SeedLoadingException("SEED_PLAN_CONTEXT_MISMATCH");
        if (conflictPolicy != SeedConflictPolicy.ERROR)
            throw new SeedLoadingException("SEED_PLAN_CONFLICT_POLICY: exact execution requires ERROR");
        SeedResolution resolution = loader.resolveSeedPacks(context);
        Map<SeedPackDescriptor, Map<Dataset, DatasetPayload>> prepared = new LinkedHashMap<>();
        Map<String, SeedPackDescriptor> seen = new LinkedHashMap<>();
        for (SeedPlan.Pack pack : plan.packs()) {
            SeedPackDescriptor descriptor = resolution.resolve(SeedPackRef.exact(pack.name(), pack.version()));
            descriptor.getManifest().validate();
            if (seen.containsKey(pack.name())) throw new SeedLoadingException("SEED_PLAN_DUPLICATE: " + pack.name());
            if (!ScopeMatcher.isApplicable(descriptor, context))
                throw new SeedLoadingException("SEED_PLAN_SCOPE_MISMATCH: " + descriptor.identity());
            // Verify dependency closure and ordering against the pinned descriptors, not latest versions.
            for (SeedPackRef include : descriptor.getManifest().getIncludeRefs()) {
                SeedPackDescriptor dependency = seen.get(include.getName());
                if (dependency == null || !include.matches(Semver.parse(dependency.getManifest().getVersion())))
                    throw new SeedLoadingException("SEED_PLAN_DEPENDENCY_MISMATCH: " + include.getName());
            }
            List<Dataset> datasets = loader.orderDatasetsByDependencies(descriptor);
            datasets.forEach(SeedCollectionResolver::ensureCollectionSet);
            if (!pack.manifestSha256().equals(manifestHash(descriptor)) || datasets.size() != pack.datasets().size())
                throw new SeedLoadingException("SEED_PLAN_MANIFEST_DRIFT: " + descriptor.identity());
            Map<Dataset, DatasetPayload> payloads = new java.util.IdentityHashMap<>();
            for (int i = 0; i < datasets.size(); i++) {
                Dataset dataset = datasets.get(i);
                loader.buildTransforms(dataset);
                SeedPlan.Dataset expected = pack.datasets().get(i);
                DatasetPayload payload = loader.readDataset(descriptor, dataset);
                if (!expected.file().equals(dataset.getFile()) || !expected.sha256().equals(payload.checksum()))
                    throw new SeedLoadingException("SEED_PLAN_DATASET_DRIFT: " + descriptor.identity() + "/" + dataset.getFile());
                payloads.put(dataset, payload);
            }
            seen.put(pack.name(), descriptor);
            prepared.put(descriptor, payloads);
        }
        prepared.forEach((descriptor, payloads) -> loader.applyDatasets(descriptor, context, payloads));
    }

    private String contextHash(SeedContext context) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("realm", context.getRealm());
        fields.put("tenantId", context.getTenantId().orElse(null));
        fields.put("orgRefName", context.getOrgRefName().orElse(null));
        fields.put("accountId", context.getAccountId().orElse(null));
        fields.put("ownerId", context.getOwnerId().orElse(null));
        fields.put("variables", new java.util.TreeMap<>(context.getVariables()));
        return hashJson(fields);
    }

    private String manifestHash(SeedPackDescriptor descriptor) {
        // Paths may change across hosts. Hash manifest semantics including ordered index keys.
        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.valueToTree(descriptor.getManifest());
        node.remove("sourceDescription");
        return hashJson(node);
    }

    private String hashJson(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(value)));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new SeedLoadingException("SEED_PLAN_HASH_FAILED", e);
        }
    }

}
