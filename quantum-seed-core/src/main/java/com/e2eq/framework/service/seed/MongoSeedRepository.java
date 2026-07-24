package com.e2eq.framework.service.seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bson.Document;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.WriteModel;

/**
 * MongoDB implementation of {@link SeedRepository}.
 */
public final class MongoSeedRepository implements SeedRepository {

    private final MongoClient mongoClient;

    public MongoSeedRepository(MongoClient mongoClient) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "mongoClient");
    }

    @Override
    public void ensureIndexes(SeedContext context, SeedPackManifest.Dataset dataset, SeedPackManifest.Index index) {
        MongoCollection<Document> collection = getCollection(context, dataset);
        Document keysDoc = new Document();
        index.getKeys().forEach(keysDoc::append);
        String name = index.getName();
        // Idempotent create: check existing indexes by name first
        boolean exists = false;
        try (MongoCursor<Document> cursor = collection.listIndexes().iterator()) {
            while (cursor.hasNext()) {
                Document idx = cursor.next();
                if (Objects.equals(name, idx.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            IndexOptions options = new IndexOptions().name(name).unique(index.isUnique());
            collection.createIndex(keysDoc, options);
        }
    }

    @Override
    public void upsertRecord(SeedContext context, SeedPackManifest.Dataset dataset, Map<String, Object> record) {
        MongoCollection<Document> collection = getCollection(context, dataset);
        Document document = new Document(convertValue(record));
        try {
            if (dataset.isUpsert()) {
                Document filter = new Document();
                for (String key : dataset.getNaturalKey()) {
                    if (!document.containsKey(key)) {
                        throw new IllegalStateException("Seed record missing natural key field '" + key + "' for collection " + dataset.getCollection());
                    }
                    filter.append(key, document.get(key));
                }
                collection.replaceOne(filter, document, new ReplaceOptions().upsert(true));
            } else {
                // Insert-only: rely on unique index and handle duplicate key gracefully
                collection.insertOne(document);
            }
        } catch (MongoWriteException mwx) {
            // Duplicate key (11000) -> skip silently in insert-only mode
            if (!dataset.isUpsert() && mwx.getError() != null && mwx.getError().getCode() == 11000) {
                return;
            }
            throw new SeedLoadingException("MongoDB write failed for collection " + dataset.getCollection() + ": " + mwx.getMessage(), mwx);
        } catch (MongoException ex) {
            throw new SeedLoadingException("MongoDB write failed for collection " + dataset.getCollection() + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public void upsertBatch(SeedContext context, SeedPackManifest.Dataset dataset, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;
        MongoCollection<Document> collection = getCollection(context, dataset);
        List<WriteModel<Document>> ops = new ArrayList<>(records.size());
        for (Map<String, Object> rec : records) {
            Document doc = new Document(convertValue(rec));
            Document filter = new Document();
            for (String key : dataset.getNaturalKey()) {
                if (!doc.containsKey(key)) {
                    throw new IllegalStateException("Seed record missing natural key field '" + key + "' for collection " + dataset.getCollection());
                }
                filter.append(key, doc.get(key));
            }
            ops.add(new ReplaceOneModel<>(filter, doc, new ReplaceOptions().upsert(true)));
        }
        try {
            collection.bulkWrite(ops, new BulkWriteOptions().ordered(false));
        } catch (MongoBulkWriteException bwx) {
            throw new SeedLoadingException("MongoDB bulk upsert failed for collection " + dataset.getCollection() + ": " + bwx.getMessage(), bwx);
        }
    }

    @Override
    public void insertBatch(SeedContext context, SeedPackManifest.Dataset dataset, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;
        MongoCollection<Document> collection = getCollection(context, dataset);
        List<WriteModel<Document>> ops = new ArrayList<>(records.size());
        for (Map<String, Object> rec : records) {
            Document doc = new Document(convertValue(rec));
            ops.add(new InsertOneModel<>(doc));
        }
        try {
            collection.bulkWrite(ops, new BulkWriteOptions().ordered(false));
        } catch (MongoBulkWriteException bwx) {
            // For insert-only, treat duplicate key errors as benign and ignore them if all errors are 11000
            boolean onlyDupKeys = bwx.getWriteErrors() != null && !bwx.getWriteErrors().isEmpty()
                    && bwx.getWriteErrors().stream().allMatch(e -> e.getCode() == 11000);
            if (onlyDupKeys) {
                return; // ignore duplicate inserts
            }
            throw new SeedLoadingException("MongoDB bulk insert failed for collection " + dataset.getCollection() + ": " + bwx.getMessage(), bwx);
        }
    }

    private MongoCollection<Document> getCollection(SeedContext context, SeedPackManifest.Dataset dataset) {
        MongoDatabase database = mongoClient.getDatabase(context.getRealm());
        return database.getCollection(dataset.getCollection());
    }

    private Map<String, Object> convertValue(Map<String, Object> value) {
        Map<String, Object> converted = new LinkedHashMap<>();
        value.forEach((key, v) -> converted.put(key, convertScalar(v)));
        return converted;
    }

    @SuppressWarnings("unchecked")
    private Object convertScalar(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((k, v) -> nested.put(Objects.toString(k), convertScalar(v)));
            return new Document(nested);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::convertScalar).toList();
        }
        return value;
    }
}
