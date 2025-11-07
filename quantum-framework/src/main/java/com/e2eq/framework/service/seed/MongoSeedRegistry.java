package com.e2eq.framework.service.seed;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import io.quarkus.logging.Log;
import org.bson.Document;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;

/**
 * Stores seeding metadata inside the tenant database.
 */
public final class MongoSeedRegistry implements SeedRegistry {

    private static final String COLLECTION = "_seed_registry";

    private final MongoClient mongoClient;

    public MongoSeedRegistry(MongoClient mongoClient) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "mongoClient");
    }

    @Override
    public boolean shouldApply(SeedContext context,
                               SeedPackManifest manifest,
                               SeedPackManifest.Dataset dataset,
                               String checksum) {
        Document existing = getCollection(context).find(Filters.and(
                Filters.eq("seedPack", manifest.getSeedPack()),
                Filters.eq("version", manifest.getVersion()),
                Filters.eq("appliedToRealm", context.getRealm()),
                Filters.eq("dataset", dataset.getCollection()))).first();
        if (existing == null) {
            return true;
        }
        return !Objects.equals(existing.getString("checksum"), checksum);
    }

    @Override
    public Optional<String> getLastAppliedChecksum(SeedContext context,
                                                   SeedPackManifest manifest,
                                                   SeedPackManifest.Dataset dataset) {
        Document existing = getCollection(context).find(Filters.and(
                Filters.eq("seedPack", manifest.getSeedPack()),
                Filters.eq("version", manifest.getVersion()),
                Filters.eq("appliedToRealm", context.getRealm()),
                Filters.eq("dataset", dataset.getCollection()))).first();
        return Optional.ofNullable(existing == null ? null : existing.getString("checksum"));
    }

    @Override
    public void recordApplied(SeedContext context,
                              SeedPackManifest manifest,
                              SeedPackManifest.Dataset dataset,
                              String checksum,
                              int recordsApplied) {
        Document doc = new Document()
                .append("seedPack", manifest.getSeedPack())
                .append("version", manifest.getVersion())
                .append("dataset", dataset.getCollection())
                .append("checksum", checksum)
                .append("records", recordsApplied)
                .append("appliedToRealm", context.getRealm())
                .append("appliedAt", Instant.now());
        getCollection(context).replaceOne(Filters.and(
                        Filters.eq("seedPack", manifest.getSeedPack()),
                        Filters.eq("version", manifest.getVersion()),
                        Filters.eq("dataset", dataset.getCollection())),
                doc,
                new ReplaceOptions().upsert(true));
    }

    private MongoCollection<Document> getCollection(SeedContext context) {
        Log.infof("Getting  collection %s for realm %s", COLLECTION,context.getRealm());
        MongoDatabase database = mongoClient.getDatabase(context.getRealm());
        MongoCollection<Document> collection = database.getCollection(COLLECTION);
        collection.createIndex(new Document("seedPack", 1).append("version", 1).append("dataset", 1));
        return collection;
    }
}
