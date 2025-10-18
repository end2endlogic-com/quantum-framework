package com.e2eq.framework.service.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.e2eq.framework.service.seed.SeedPackManifest;
import com.e2eq.framework.service.seed.SeedPackManifest.Archetype;
import com.e2eq.framework.service.seed.SeedPackManifest.Dataset;
import com.e2eq.framework.service.seed.SeedPackManifest.Transform;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.quarkus.logging.Log;

import org.semver4j.Semver;

/**
 * Coordinates seed pack discovery, dependency resolution and dataset application.
 */
public final class SeedLoader {

    private final List<SeedSource> seedSources;
    private final SeedRepository seedRepository;
    private final SeedRegistry seedRegistry;
    private final Map<String, SeedTransformFactory> transformFactories;
    private final ObjectMapper objectMapper;
    private final SeedResourceRouter resourceRouter;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private SeedLoader(Builder builder) {
        this.seedSources = List.copyOf(builder.seedSources);
        this.seedRepository = Objects.requireNonNull(builder.seedRepository, "seedRepository");
        this.seedRegistry = Objects.requireNonNull(builder.seedRegistry, "seedRegistry");
        this.transformFactories = Map.copyOf(builder.transformFactories);
        this.objectMapper = builder.objectMapper;
        this.resourceRouter = new SeedResourceRouter(this.seedSources);
    }

    public static Builder builder() {
        return new Builder();
    }

    public void applyArchetype(String archetypeName, SeedContext context) {
        Objects.requireNonNull(archetypeName, "archetypeName");
        SeedResolution resolution = resolveSeedPacks(context);
        SeedPackDescriptor descriptor = resolution.findArchetype(archetypeName)
                .orElseThrow(() -> new SeedLoadingException("Archetype " + archetypeName + " not found"));
        Archetype archetype = descriptor.getManifest().findArchetype(archetypeName).orElseThrow();
        List<SeedPackRef> refs = new ArrayList<>(archetype.getIncludeRefs());
        refs.add(SeedPackRef.exact(descriptor.getManifest().getSeedPack(), descriptor.getManifest().getVersion()));
        apply(refs, context, resolution);
    }

    public void apply(List<SeedPackRef> packRefs, SeedContext context) {
        apply(packRefs, context, resolveSeedPacks(context));
    }

    private void apply(List<SeedPackRef> packRefs, SeedContext context, SeedResolution resolution) {
        Objects.requireNonNull(packRefs, "packRefs");
        Objects.requireNonNull(context, "context");

        Log.infof("Seed context realm {0}, applying {1} seed pack(s)", context.getRealmId(), packRefs.size());
        Log.infof("Seedcontext.tenantId: %s, orgRefName: %s, accountId: %s, ownerId: %s",
           context.getTenantId().isPresent() ? context.getTenantId().get() : "null",
           context.getOrgRefName().isPresent()?  context.getOrgRefName().get(): "null",
           context.getAccountId().isPresent() ?  context.getAccountId().get() : "null",
           context.getOwnerId().isPresent() ?  context.getOwnerId().get() : "null");

        if (packRefs.isEmpty()) {
            Log.infov("No seed packs requested for realm {0}", context.getRealmId());
            return;
        }
        Set<String> applied = new LinkedHashSet<>();
        for (SeedPackRef ref : packRefs) {
            SeedPackDescriptor descriptor = resolution.resolve(ref);
            applyDescriptor(descriptor, context, resolution, applied, new ArrayDeque<>());
        }
    }

    private void applyDescriptor(SeedPackDescriptor descriptor,
                                 SeedContext context,
                                 SeedResolution resolution,
                                 Set<String> applied,
                                 Deque<String> stack) {
        String identity = descriptor.identity();
        if (!applied.add(identity)) {
            Log.debugf("Seed pack %s already applied", identity);
            return;
        }
        if (stack.contains(identity)) {
            throw new SeedLoadingException("Cyclic seed pack dependency detected: " + stack + " -> " + identity);
        }
        stack.push(identity);
        for (SeedPackRef include : descriptor.getManifest().getIncludeRefs()) {
            SeedPackDescriptor includeDescriptor = resolution.resolve(include);
            applyDescriptor(includeDescriptor, context, resolution, applied, stack);
        }
        stack.pop();
        applyDatasets(descriptor, context);
    }

    private void applyDatasets(SeedPackDescriptor descriptor, SeedContext context) {
        List<Dataset> datasets = descriptor.getManifest().getDatasets();
        if (datasets.isEmpty()) {
            Log.debugf("Seed pack %s has no datasets", descriptor.identity());
            return;
        }
        for (Dataset dataset : datasets) {
            // Ensure collection is set; when modelClass is provided, derive collection name from @Entity or class simple name
            deriveCollectionIfMissing(dataset);
            Log.infov("Applying seed dataset {0}/{1} into realm {2}", descriptor.getManifest().getSeedPack(), dataset.getCollection(), context.getRealmId());
            DatasetPayload payload = readDataset(descriptor, dataset);
            if (!seedRegistry.shouldApply(context, descriptor.getManifest(), dataset, payload.checksum())) {
                Log.debugf("Dataset %s already applied with matching checksum", dataset.getCollection());
                continue;
            }
            dataset.getRequiredIndexes().forEach(index -> seedRepository.ensureIndexes(context, dataset, index));
            List<SeedTransform> transforms = buildTransforms(dataset);
            int appliedCount = 0;
            for (Map<String, Object> record : payload.records()) {
                Map<String, Object> current = new LinkedHashMap<>(record);
                for (SeedTransform transform : transforms) {
                    if (current == null) {
                        break;
                    }
                    current = transform.apply(current, context, dataset);
                }
                if (current == null || current.isEmpty()) {
                    continue;
                }
                seedRepository.upsertRecord(context, dataset, current);
                appliedCount++;
            }
            seedRegistry.recordApplied(context, descriptor.getManifest(), dataset, payload.checksum(), appliedCount);
            Log.infov("Applied {0} records to {1}", appliedCount, dataset.getCollection());
        }
    }

    private List<SeedTransform> buildTransforms(Dataset dataset) {
        if (dataset.getTransforms().isEmpty()) {
            return List.of();
        }
        List<SeedTransform> transforms = new ArrayList<>(dataset.getTransforms().size());
        for (Transform transformDef : dataset.getTransforms()) {
            SeedTransformFactory factory = transformFactories.get(transformDef.getType());
            if (factory == null) {
                throw new SeedLoadingException("Unknown transform type '" + transformDef.getType() + "'");
            }
            transforms.add(factory.create(transformDef));
        }
        return transforms;
    }

    private DatasetPayload readDataset(SeedPackDescriptor descriptor, Dataset dataset) {
        try (InputStream in = resourceRouter.open(descriptor, dataset.getFile())) {
            byte[] bytes = in.readAllBytes();
            String checksum = checksum(bytes);
            List<Map<String, Object>> records = parseDataset(bytes, dataset);
            return new DatasetPayload(checksum, records);
        } catch (IOException e) {
            throw new SeedLoadingException("Failed to read dataset " + dataset.getFile() + " for seed pack " + descriptor.identity(), e);
        }
    }

    private List<Map<String, Object>> parseDataset(byte[] bytes, Dataset dataset) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        try {
            String trimmed = content.trim();
            if (trimmed.isEmpty()) {
                return List.of();
            }
            if (trimmed.startsWith("[")) {
                ArrayNode arrayNode = (ArrayNode) objectMapper.readTree(trimmed);
                List<Map<String, Object>> records = new ArrayList<>(arrayNode.size());
                for (JsonNode node : arrayNode) {
                    records.add(objectMapper.convertValue(node, MAP_TYPE));
                }
                return records;
            }
            List<Map<String, Object>> records = new ArrayList<>();
            for (String line : content.split("\r?\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                records.add(objectMapper.convertValue(node, MAP_TYPE));
            }
            return records;
        } catch (IOException e) {
            throw new SeedLoadingException("Invalid dataset format for collection " + dataset.getCollection() + ": " + e.getMessage(), e);
        }
    }

    private String checksum(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // Derive collection from modelClass annotation if missing
    private void deriveCollectionIfMissing(SeedPackManifest.Dataset dataset) {
        String coll = dataset.getCollection();
        if (coll != null && !coll.isBlank()) {
            return;
        }
        String mc = null;
        try {
            var getter = SeedPackManifest.Dataset.class.getDeclaredMethod("getModelClass");
            Object val = getter.invoke(dataset);
            mc = (val instanceof String s) ? s : null;
        } catch (Exception ignore) {
        }
        if (mc == null || mc.isBlank()) {
            return;
        }
        try {
            Class<?> cls = Class.forName(mc);
            // Try Morphia @Entity annotation
            try {
                dev.morphia.annotations.Entity ann = cls.getAnnotation(dev.morphia.annotations.Entity.class);
                if (ann != null) {
                    String name = ann.value();
                    if (name != null && !name.isBlank()) {
                        dataset.setCollection(name);
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // Morphia not on classpath or annotation not present
            }
            dataset.setCollection(cls.getSimpleName());
        } catch (ClassNotFoundException e) {
            // Fallback to last segment of FQN as a best-effort
            String simple = mc.contains(".") ? mc.substring(mc.lastIndexOf('.') + 1) : mc;
            dataset.setCollection(simple);
        }
    }

    private SeedResolution resolveSeedPacks(SeedContext context) {
        Map<String, List<SeedPackDescriptor>> descriptors = new HashMap<>();
        for (SeedSource source : seedSources) {
            try {
                for (SeedPackDescriptor descriptor : source.loadSeedPacks(context)) {
                    SeedPackManifest manifest = descriptor.getManifest();
                    descriptors.computeIfAbsent(manifest.getSeedPack(), key -> new ArrayList<>()).add(descriptor);
                }
            } catch (IOException e) {
                throw new SeedLoadingException("Failed loading seed packs from source " + source.getId(), e);
            }
        }
        return new SeedResolution(descriptors);
    }

    private static final class DatasetPayload {
        private final String checksum;
        private final List<Map<String, Object>> records;

        private DatasetPayload(String checksum, List<Map<String, Object>> records) {
            this.checksum = checksum;
            this.records = Collections.unmodifiableList(records);
        }

        public String checksum() {
            return checksum;
        }

        public List<Map<String, Object>> records() {
            return records;
        }
    }

    private static final class SeedResolution {
        private final Map<String, List<SeedPackDescriptor>> byName;

        private SeedResolution(Map<String, List<SeedPackDescriptor>> byName) {
            this.byName = new HashMap<>();
            byName.forEach((name, descriptors) -> this.byName.put(name, descriptors.stream()
                    .sorted(Comparator.comparing(d -> Semver.parse(d.getManifest().getVersion())))
                    .toList()));
        }

        private SeedPackDescriptor resolve(SeedPackRef ref) {
            List<SeedPackDescriptor> descriptors = byName.get(ref.getName());
            if (descriptors == null || descriptors.isEmpty()) {
                throw new SeedLoadingException("No seed pack named " + ref.getName() + " found");
            }
            for (int i = descriptors.size() - 1; i >= 0; i--) {
                SeedPackDescriptor descriptor = descriptors.get(i);
                Semver version = Semver.parse(descriptor.getManifest().getVersion());
                if (ref.matches(version)) {
                    return descriptor;
                }
            }
            throw new SeedLoadingException("No version of seed pack " + ref.getName() + " matches " + Optional.ofNullable(ref.getSpecification()).orElse("latest"));
        }

        private Optional<SeedPackDescriptor> findArchetype(String archetype) {
            return byName.values().stream()
                    .flatMap(List::stream)
                    .filter(descriptor -> descriptor.getManifest().findArchetype(archetype).isPresent())
                    .max(Comparator.comparing(d -> Semver.parse(d.getManifest().getVersion())));
        }
    }

    public static final class Builder {
        private final List<SeedSource> seedSources = new ArrayList<>();
        private final Map<String, SeedTransformFactory> transformFactories = new HashMap<>();
        private SeedRepository seedRepository;
        private SeedRegistry seedRegistry = SeedRegistry.noop();
        private ObjectMapper objectMapper = new ObjectMapper();

        private Builder() {
            registerTransformFactory("tenantSubstitution", new TenantSubstitutionTransform.Factory());
        }

        public Builder addSeedSource(SeedSource source) {
            this.seedSources.add(Objects.requireNonNull(source, "source"));
            return this;
        }

        public Builder seedRepository(SeedRepository seedRepository) {
            this.seedRepository = Objects.requireNonNull(seedRepository, "seedRepository");
            return this;
        }

        public Builder seedRegistry(SeedRegistry seedRegistry) {
            this.seedRegistry = Objects.requireNonNull(seedRegistry, "seedRegistry");
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            return this;
        }

        public Builder registerTransformFactory(String type, SeedTransformFactory factory) {
            transformFactories.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(factory, "factory"));
            return this;
        }

        public SeedLoader build() {
            if (seedRepository == null) {
                throw new IllegalStateException("SeedRepository is required");
            }
            return new SeedLoader(this);
        }
    }
}
