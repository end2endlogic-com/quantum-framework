package com.e2eq.framework.service.seed;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
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

import com.e2eq.framework.service.seed.SeedPackManifest.Archetype;
import com.e2eq.framework.service.seed.SeedPackManifest.Dataset;
import com.e2eq.framework.service.seed.SeedPackManifest.Transform;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
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
    private final SeedConflictPolicy conflictPolicy;
    private final int batchSize;
    private final SeedMetrics seedMetrics;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private SeedLoader(Builder builder) {
        this.seedSources = List.copyOf(builder.seedSources);
        this.seedRepository = Objects.requireNonNull(builder.seedRepository, "seedRepository");
        this.seedRegistry = Objects.requireNonNull(builder.seedRegistry, "seedRegistry");
        this.transformFactories = Map.copyOf(builder.transformFactories);
        this.objectMapper = builder.objectMapper;
        this.resourceRouter = new SeedResourceRouter(this.seedSources);
        this.conflictPolicy = builder.conflictPolicy;
        this.batchSize = builder.batchSize;
        this.seedMetrics = builder.seedMetrics;
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

        Log.infof("Seed context realm %s, applying %s seed pack(s)", context.getRealm(), packRefs.size());
        // Avoid logging potentially sensitive identifiers at info level; use debug instead
        Log.debugf("Seed context.tenantId: %s, orgRefName: %s, accountId: %s, ownerId: %s",
           context.getTenantId().orElse("null"),
           context.getOrgRefName().orElse("null"),
           context.getAccountId().orElse("null"),
           context.getOwnerId().orElse("null"));

        if (packRefs.isEmpty()) {
            Log.infov("No seed packs requested for realm {0}", context.getRealm());
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
            if (dataset.getCollection() == null || dataset.getCollection().isBlank()) {
                throw new SeedLoadingException("Dataset collection is not specified and could not be derived for seed pack "
                        + descriptor.identity());
            }
            Log.infov("Applying seed dataset {0}/{1} into realm {2}", descriptor.getManifest().getSeedPack(), dataset.getCollection(), context.getRealm());
            long parseStart = System.currentTimeMillis();
            DatasetPayload payload = readDataset(descriptor, dataset);
            long parseDur = System.currentTimeMillis() - parseStart;
            if (seedMetrics != null) {
                seedMetrics.recordDatasetParse(
                        descriptor.getManifest().getSeedPack(),
                        dataset.getCollection(),
                        context.getRealm(),
                        parseDur,
                        payload.records().size());
            }

            // Determine previous checksum if any, to decide conflict handling
            Optional<String> last = seedRegistry.getLastAppliedChecksum(context, descriptor.getManifest(), dataset);
            if (last.isPresent()) {
                String lastChecksum = last.get();
                if (Objects.equals(lastChecksum, payload.checksum())) {
                    Log.debugf("Dataset %s already applied with matching checksum", dataset.getCollection());
                    if (seedMetrics != null) {
                        seedMetrics.recordDatasetSkipped(
                                descriptor.getManifest().getSeedPack(),
                                dataset.getCollection(),
                                context.getRealm(),
                                "checksumEqual");
                    }
                    continue;
                }
                // Conflict: previously applied dataset differs from current
                switch (conflictPolicy) {
                    case EXISTING_WINS -> {
                        Log.warnf("Skipping dataset %s because existing checksum %s differs from incoming %s and policy is EXISTING_WINS", dataset.getCollection(), lastChecksum, payload.checksum());
                        if (seedMetrics != null) {
                            seedMetrics.recordDatasetSkipped(
                                    descriptor.getManifest().getSeedPack(),
                                    dataset.getCollection(),
                                    context.getRealm(),
                                    "policyExistingWins");
                        }
                        continue;
                    }
                    case ERROR -> {
                        throw new SeedLoadingException(String.format("Conflict applying dataset %s: existing checksum %s differs from incoming %s. Set conflictPolicy=SEED_WINS to override or EXISTING_WINS to skip.", dataset.getCollection(), lastChecksum, payload.checksum()));
                    }
                    case SEED_WINS -> {
                        Log.warnf("Overwriting dataset %s despite checksum conflict (existing %s vs incoming %s) due to policy SEED_WINS", dataset.getCollection(), lastChecksum, payload.checksum());
                    }
                }
            } else {
                // No prior record; for backward compatibility also respect registry's shouldApply if implemented differently
                if (!seedRegistry.shouldApply(context, descriptor.getManifest(), dataset, payload.checksum())) {
                    Log.debugf("Dataset %s already applied with matching checksum", dataset.getCollection());
                    if (seedMetrics != null) {
                        seedMetrics.recordDatasetSkipped(
                                descriptor.getManifest().getSeedPack(),
                                dataset.getCollection(),
                                context.getRealm(),
                                "registrySaysNo");
                    }
                    continue;
                } else {
                   Log.infof("!! > should apply came back true, Applying dataset %s into realm %s", dataset.getCollection(), context.getRealm());
                }
            }

            // Validate natural key presence in insert-only mode to avoid accidental duplicates
            if (!dataset.isUpsert()) {
                List<String> nk = dataset.getNaturalKey();
                if (nk == null || nk.isEmpty()) {
                    throw new SeedLoadingException("Insert-only dataset '" + dataset.getCollection() + "' is missing naturalKey definition");
                }
            }

            // Ensure indexes with timing per index
            dataset.getRequiredIndexes().forEach(index -> {
                long idxStart = System.currentTimeMillis();
                seedRepository.ensureIndexes(context, dataset, index);
                long idxDur = System.currentTimeMillis() - idxStart;
                if (seedMetrics != null) {
                    try {
                        seedMetrics.recordIndexEnsureTime(
                                descriptor.getManifest().getSeedPack(),
                                dataset.getCollection(),
                                context.getRealm(),
                                index.getName(),
                                idxDur);
                    } catch (Exception ignore) { }
                }
            });
            List<SeedTransform> transforms = buildTransforms(dataset);
            int appliedCount = 0;
            int dropCount = 0;
            List<Map<String, Object>> buffer = new ArrayList<>(Math.max(16, batchSize));
            for (Map<String, Object> record : payload.records()) {
                Map<String, Object> current = new LinkedHashMap<>(record);
                for (SeedTransform transform : transforms) {
                    if (current == null) {
                        break;
                    }
                    current = transform.apply(current, context, dataset);
                    if (current == null) {
                        // A transform decided to drop the record; log warning with minimal context
                        Log.warnf("Seed record dropped by transform %s for collection %s (seedPack=%s)",
                                transform.getClass().getSimpleName(),
                                dataset.getCollection(),
                                descriptor.getManifest().getSeedPack());
                        dropCount++;
                    }
                }
                if (current == null || current.isEmpty()) {
                    continue;
                }
                buffer.add(current);
                if (buffer.size() >= batchSize) {
                    long bwStart = System.currentTimeMillis();
                    boolean up = dataset.isUpsert();
                    if (up) {
                        seedRepository.upsertBatch(context, dataset, buffer);
                    } else {
                        seedRepository.insertBatch(context, dataset, buffer);
                    }
                    long bwDur = System.currentTimeMillis() - bwStart;
                    if (seedMetrics != null) {
                        seedMetrics.recordBatchWrite(
                                descriptor.getManifest().getSeedPack(),
                                dataset.getCollection(),
                                context.getRealm(),
                                up,
                                bwDur,
                                buffer.size());
                    }
                    appliedCount += buffer.size();
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                long bwStart = System.currentTimeMillis();
                boolean up = dataset.isUpsert();
                if (up) {
                    seedRepository.upsertBatch(context, dataset, buffer);
                } else {
                    seedRepository.insertBatch(context, dataset, buffer);
                }
                long bwDur = System.currentTimeMillis() - bwStart;
                if (seedMetrics != null) {
                    seedMetrics.recordBatchWrite(
                            descriptor.getManifest().getSeedPack(),
                            dataset.getCollection(),
                            context.getRealm(),
                            up,
                            bwDur,
                            buffer.size());
                }
                appliedCount += buffer.size();
                buffer.clear();
            }
            seedRegistry.recordApplied(context, descriptor.getManifest(), dataset, payload.checksum(), appliedCount);
            Log.infov("Applied {0} records to {1}", appliedCount, dataset.getCollection());
            if (seedMetrics != null) {
                seedMetrics.recordDatasetApplication(
                        descriptor.getManifest().getSeedPack(),
                        dataset.getCollection(),
                        context.getRealm(),
                        true,
                        appliedCount);
                if (dropCount > 0) {
                    seedMetrics.incrementTransformDrops(
                            descriptor.getManifest().getSeedPack(),
                            dataset.getCollection(),
                            context.getRealm(),
                            dropCount);
                }
            }
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
        try (InputStream raw = resourceRouter.open(descriptor, dataset.getFile())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream din = new DigestInputStream(new BufferedInputStream(raw), digest)) {
                List<Map<String, Object>> records = parseDatasetStream(din, dataset);
                String checksum = HexFormat.of().formatHex(digest.digest());
                return new DatasetPayload(checksum, records);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new SeedLoadingException("Failed to read dataset " + dataset.getFile() + " for seed pack " + descriptor.identity(), e);
        }
    }

    private List<Map<String, Object>> parseDatasetStream(InputStream in, Dataset dataset) {
        try {
            // Peek first non-whitespace byte to detect JSON array ('[') vs JSONL
            in.mark(1);
            int first;
            do {
                in.mark(1);
                first = in.read();
            } while (first != -1 && Character.isWhitespace(first));
            if (first == -1) {
                return List.of();
            }
            // Reset to the start of the detected token
            in.reset();

            if (first == '[') {
                // Stream parse JSON array
                List<Map<String, Object>> records = new ArrayList<>();
                try (JsonParser parser = objectMapper.getFactory().createParser(in)) {
                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        throw new SeedLoadingException("Expected JSON array for dataset '" + dataset.getCollection() + "'");
                    }
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        Map<String, Object> rec = parser.readValueAs(MAP_TYPE);
                        records.add(rec);
                    }
                }
                return records;
            } else {
                // Treat as JSONL: one JSON object per line
                List<Map<String, Object>> records = new ArrayList<>();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isBlank()) continue;
                        JsonNode node = objectMapper.readTree(line);
                        records.add(objectMapper.convertValue(node, MAP_TYPE));
                    }
                }
                return records;
            }
        } catch (IOException e) {
            throw new SeedLoadingException("Invalid dataset format for collection " + dataset.getCollection() + ": " + e.getMessage(), e);
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
        // Safer default: avoid silent overwrites unless explicitly configured by caller
        private SeedConflictPolicy conflictPolicy = SeedConflictPolicy.ERROR;
        private int batchSize = 200;
        private SeedMetrics seedMetrics;

        private Builder() {
            registerTransformFactory("tenantSubstitution", new TenantSubstitutionTransform.Factory());
            // Secure credential upsert via UserManagement
            registerTransformFactory("credentialUpsert", new CredentialUpsertTransform.Factory());
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

        public Builder conflictPolicy(SeedConflictPolicy policy) {
            this.conflictPolicy = Objects.requireNonNull(policy, "conflictPolicy");
            return this;
        }

        public Builder batchSize(int batchSize) {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize must be positive");
            }
            this.batchSize = batchSize;
            return this;
        }

        public Builder seedMetrics(SeedMetrics seedMetrics) {
            this.seedMetrics = seedMetrics;
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
