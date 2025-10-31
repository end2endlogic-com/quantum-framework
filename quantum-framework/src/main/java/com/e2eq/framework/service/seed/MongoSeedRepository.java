package com.e2eq.framework.service.seed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bson.Document;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;

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
        IndexOptions options = new IndexOptions().name(index.getName()).unique(index.isUnique());
        collection.createIndex(keysDoc, options);
    }

    @Override
    public void upsertRecord(SeedContext context, SeedPackManifest.Dataset dataset, Map<String, Object> record) {
        MongoCollection<Document> collection = getCollection(context, dataset);
        Document document = new Document(convertValue(record));
        Document filter = new Document();
        for (String key : dataset.getNaturalKey()) {
            if (!document.containsKey(key)) {
                throw new IllegalStateException("Seed record missing natural key field '" + key + "' for collection " + dataset.getCollection());
            }
            filter.append(key, document.get(key));
        }
        try {
            if (dataset.isUpsert()) {
                collection.replaceOne(filter, document, new ReplaceOptions().upsert(true));
            } else {
                collection.insertOne(document);
            }
        } catch (MongoException ex) {
            throw new SeedLoadingException("MongoDB write failed for collection " + dataset.getCollection() + ": " + ex.getMessage(), ex);
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
