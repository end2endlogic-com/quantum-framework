package com.e2eq.framework.service.seed;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.semver4j.Semver;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only inspection service for pending seed packs.
 * Reports dataset-level and record-level issues before an apply operation mutates state.
 */
@ApplicationScoped
public class SeedVerificationService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Inject
    SeedDiscoveryService seedDiscoveryService;

    @Inject
    SeedRegistry seedRegistry;

    @Inject
    SeedDatasetValidator seedDatasetValidator;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MongoClient mongoClient;

    @Inject
    Instance<SeedSource> seedSources;

    public VerifyResult verifyLatestApplicable(SeedContext context, String filterCsv) {
        try {
            Map<String, SeedPackDescriptor> latestApplicable = seedDiscoveryService.discoverLatestApplicable(context, filterCsv);
            List<VerifyPackResult> packs = new ArrayList<>();
            for (SeedPackDescriptor descriptor : latestApplicable.values()) {
                VerifyPackResult pack = verifyDescriptorIfPending(context, descriptor);
                if (pack != null) {
                    packs.add(pack);
                }
            }
            packs.sort(Comparator.comparing(VerifyPackResult::seedPack, String.CASE_INSENSITIVE_ORDER));
            return summarize(context.getRealm(), packs);
        } catch (IOException e) {
            throw new SeedLoadingException("Failed to verify seed packs for realm " + context.getRealm(), e);
        }
    }

    public VerifyResult verifyOne(SeedContext context, String seedPackName) {
        try {
            List<SeedPackDescriptor> descriptors = seedDiscoveryService.filterByScope(
                    seedDiscoveryService.discoverSeedPacks(context),
                    context
            );
            SeedPackDescriptor latest = descriptors.stream()
                    .filter(d -> seedPackName.equals(d.getManifest().getSeedPack()))
                    .max(Comparator.comparing(d -> {
                        Semver v = Semver.parse(d.getManifest().getVersion());
                        return v != null ? v : Semver.parse("0.0.0");
                    }))
                    .orElseThrow(() -> new SeedLoadingException("Seed pack not found or not applicable: " + seedPackName));

            VerifyPackResult pack = verifyDescriptor(context, latest);
            return summarize(context.getRealm(), List.of(pack));
        } catch (IOException e) {
            throw new SeedLoadingException("Failed to verify seed pack " + seedPackName + " for realm " + context.getRealm(), e);
        }
    }

    private VerifyResult summarize(String realm, List<VerifyPackResult> packs) {
        int issueCount = 0;
        boolean ok = true;
        for (VerifyPackResult pack : packs) {
            issueCount += pack.issueCount();
            ok &= pack.ok();
        }
        return new VerifyResult(realm, ok, packs.size(), issueCount, packs);
    }

    private VerifyPackResult verifyDescriptorIfPending(SeedContext context, SeedPackDescriptor descriptor) {
        VerifyPackResult pack = verifyDescriptor(context, descriptor);
        return pack.datasetCount() == 0 ? null : pack;
    }

    private VerifyPackResult verifyDescriptor(SeedContext context, SeedPackDescriptor descriptor) {
        SeedPackManifest manifest = descriptor.getManifest();
        List<VerifyDatasetResult> datasets = new ArrayList<>();
        int issueCount = 0;
        boolean ok = true;

        for (SeedPackManifest.Dataset dataset : manifest.getDatasets()) {
            ensureCollectionSet(dataset);
            String checksum = seedDiscoveryService.calculateChecksum(descriptor, dataset);
            boolean pending = seedRegistry.shouldApply(context, manifest, dataset, checksum);
            if (!pending) {
                continue;
            }
            VerifyDatasetResult verified = verifyDataset(context, descriptor, dataset, checksum);
            datasets.add(verified);
            issueCount += verified.issueCount();
            ok &= verified.ok();
        }

        return new VerifyPackResult(
                manifest.getSeedPack(),
                manifest.getVersion(),
                ok,
                datasets.size(),
                issueCount,
                datasets
        );
    }

    private VerifyDatasetResult verifyDataset(SeedContext context,
                                              SeedPackDescriptor descriptor,
                                              SeedPackManifest.Dataset dataset,
                                              String checksum) {
        DatasetPayload payload = readDataset(descriptor, dataset);
        List<VerifyIssue> issues = new ArrayList<>();
        Map<Integer, List<String>> validationErrors = seedDatasetValidator.validateBatch(payload.records(), dataset);

        validationErrors.forEach((recordIndex, errors) -> {
            for (String error : errors) {
                issues.add(new VerifyIssue(
                        "ERROR",
                        "payload.invalidRecord",
                        recordIndex,
                        naturalKeyValue(payload.records().get(recordIndex), dataset.getNaturalKey()),
                        error
                ));
            }
        });

        if (dataset.isUpsert() && payload.records().stream().anyMatch(this::hasExplicitId)) {
            issues.add(new VerifyIssue(
                    "WARN",
                    "payload.explicitIdsPresent",
                    null,
                    null,
                    "Dataset includes explicit ids for an upsert flow. Prefer omitting id/_id and letting refName or the natural key drive identity."
            ));
        }

        MongoDatabase database = mongoClient.getDatabase(context.getRealm());
        MongoCollection<Document> collection = database.getCollection(dataset.getCollection());

        for (int i = 0; i < payload.records().size(); i++) {
            Map<String, Object> record = payload.records().get(i);
            issues.addAll(verifyRecordAgainstDatabase(collection, dataset, record, i));
        }

        boolean ok = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        return new VerifyDatasetResult(
                dataset.getCollection(),
                dataset.getFile(),
                checksum,
                payload.records().size(),
                ok,
                issues.size(),
                issues
        );
    }

    private List<VerifyIssue> verifyRecordAgainstDatabase(MongoCollection<Document> collection,
                                                          SeedPackManifest.Dataset dataset,
                                                          Map<String, Object> record,
                                                          int recordIndex) {
        List<VerifyIssue> issues = new ArrayList<>();
        List<String> naturalKey = dataset.getNaturalKey();
        Document naturalKeyFilter = buildNaturalKeyFilter(record, naturalKey);
        Document existingByNaturalKey = naturalKeyFilter == null ? null : collection.find(naturalKeyFilter).limit(1).first();

        ObjectId incomingId = extractObjectId(record);
        Document existingById = incomingId == null ? null : collection.find(new Document("_id", incomingId)).limit(1).first();

        if (existingByNaturalKey != null && incomingId != null) {
            ObjectId naturalKeyId = existingByNaturalKey.getObjectId("_id");
            if (naturalKeyId != null && !naturalKeyId.equals(incomingId)) {
                issues.add(new VerifyIssue(
                        "WARN",
                        "db.idMismatch",
                        recordIndex,
                        naturalKeyValue(record, naturalKey),
                        String.format(
                                "Existing record matched by natural key has _id=%s but the seed record specifies _id=%s. Apply may reconcile this, but removing explicit ids from the seed is safer.",
                                naturalKeyId.toHexString(),
                                incomingId.toHexString()
                        )
                ));
            }
        }

        if (existingByNaturalKey == null && incomingId != null && existingById != null) {
            issues.add(new VerifyIssue(
                    "ERROR",
                    "db.idCollision",
                    recordIndex,
                    naturalKeyValue(record, naturalKey),
                    String.format(
                            "Seed record would collide on _id=%s. That id already exists in %s, but no existing record matches the dataset natural key %s.",
                            incomingId.toHexString(),
                            dataset.getCollection(),
                            naturalKeyValue(record, naturalKey)
                    )
            ));
        }

        return issues;
    }

    private DatasetPayload readDataset(SeedPackDescriptor descriptor, SeedPackManifest.Dataset dataset) {
        List<SeedSource> sources = new ArrayList<>();
        for (SeedSource seedSource : seedSources) {
            sources.add(seedSource);
        }
        SeedResourceRouter resourceRouter = new SeedResourceRouter(sources);
        try (InputStream raw = resourceRouter.open(descriptor, dataset.getFile())) {
            byte[] data = raw.readAllBytes();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String checksum = HexFormat.of().formatHex(md.digest(data));
            List<Map<String, Object>> records = parseDatasetStream(new ByteArrayInputStream(data), dataset);
            return new DatasetPayload(checksum, records);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new SeedLoadingException("Failed to read dataset " + dataset.getFile() + " for seed pack " + descriptor.identity(), e);
        }
    }

    private List<Map<String, Object>> parseDatasetStream(InputStream in, SeedPackManifest.Dataset dataset) {
        try {
            in.mark(1);
            int first;
            do {
                in.mark(1);
                first = in.read();
            } while (first != -1 && Character.isWhitespace(first));
            if (first == -1) {
                return List.of();
            }
            in.reset();

            if (first == '[') {
                List<Map<String, Object>> records = new ArrayList<>();
                try (JsonParser parser = objectMapper.getFactory().createParser(in)) {
                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        throw new SeedLoadingException("Expected JSON array for dataset '" + dataset.getCollection() + "'");
                    }
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        records.add(parser.readValueAs(MAP_TYPE));
                    }
                }
                return records;
            }

            List<Map<String, Object>> records = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = objectMapper.readTree(line);
                    records.add(objectMapper.convertValue(node, MAP_TYPE));
                }
            }
            return records;
        } catch (IOException e) {
            throw new SeedLoadingException("Invalid dataset format for collection " + dataset.getCollection() + ": " + e.getMessage(), e);
        }
    }

    private boolean hasExplicitId(Map<String, Object> record) {
        return record.containsKey("id") || record.containsKey("_id");
    }

    private ObjectId extractObjectId(Map<String, Object> record) {
        Object rawId = record.containsKey("_id") ? record.get("_id") : record.get("id");
        if (rawId instanceof ObjectId objectId) {
            return objectId;
        }
        if (rawId instanceof String value && value.length() == 24) {
            try {
                return new ObjectId(value);
            } catch (IllegalArgumentException ignore) {
                return null;
            }
        }
        return null;
    }

    private Document buildNaturalKeyFilter(Map<String, Object> record, List<String> naturalKey) {
        if (naturalKey == null || naturalKey.isEmpty()) {
            return null;
        }
        Document filter = new Document();
        for (String key : naturalKey) {
            Object value = record.get(key);
            if (value == null) {
                return null;
            }
            filter.append(key, value);
        }
        return filter;
    }

    private String naturalKeyValue(Map<String, Object> record, List<String> naturalKey) {
        if (naturalKey == null || naturalKey.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String key : naturalKey) {
            parts.add(key + "=" + Objects.toString(record.get(key), "null"));
        }
        return String.join("|", parts);
    }

    private void ensureCollectionSet(SeedPackManifest.Dataset dataset) {
        SeedCollectionResolver.ensureCollectionSet(dataset);
    }

    private record DatasetPayload(String checksum, List<Map<String, Object>> records) {}

    public record VerifyResult(
            String realm,
            boolean ok,
            int packCount,
            int issueCount,
            List<VerifyPackResult> packs
    ) {}

    public record VerifyPackResult(
            String seedPack,
            String version,
            boolean ok,
            int datasetCount,
            int issueCount,
            List<VerifyDatasetResult> datasets
    ) {}

    public record VerifyDatasetResult(
            String collection,
            String file,
            String checksum,
            int recordCount,
            boolean ok,
            int issueCount,
            List<VerifyIssue> issues
    ) {}

    public record VerifyIssue(
            String severity,
            String code,
            Integer recordIndex,
            String naturalKey,
            String message
    ) {}
}
