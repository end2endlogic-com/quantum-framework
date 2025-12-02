package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.util.*;

/**
 * SeedRepository implementation that leverages Morphia repositories.
 * Requires modelClass to be specified in SeedPackManifest.Dataset.
 *
 * This implementation uses repository query methods for efficient natural key lookups
 * instead of loading all entities into memory.
 */
@ApplicationScoped
public class MorphiaSeedRepository implements SeedRepository {

    @Inject
    Instance<BaseMorphiaRepo<? extends UnversionedBaseModel>> allRepos;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MorphiaDataStore morphiaDataStore;

    @Inject
    MongoClient mongoClient;

    @Override
    public void ensureIndexes(SeedContext context, SeedPackManifest.Dataset dataset, SeedPackManifest.Index index) {
        Optional<BaseMorphiaRepo<? extends UnversionedBaseModel>> repoOpt = resolveRepo(dataset);

        // Ensure collection is set based on either repo or modelClass hint
        ensureCollectionSet(dataset, repoOpt);

        if (repoOpt.isPresent()) {
            try {
                // Let Morphia ensure mapped indexes for the model in the target realm
                repoOpt.get().ensureIndexes(context.getRealm(), dataset.getCollection());
                return;
            } catch (Exception e) {
                Log.warnf("Failed ensuring indexes via Morphia for %s: %s", dataset.getCollection(), e.getMessage());
                throw new SeedLoadingException("Failed to ensure indexes for " + dataset.getCollection(), e);
            }
        }

        // Fallback: Generic Mongo index creation when modelClass is not provided
        try {
            MongoDatabase database = mongoClient.getDatabase(context.getRealm());
            MongoCollection<org.bson.Document> collection = database.getCollection(dataset.getCollection());
            org.bson.Document keysDoc = new org.bson.Document();
            index.getKeys().forEach(keysDoc::append);
            IndexOptions options = new IndexOptions().name(index.getName()).unique(index.isUnique());
            collection.createIndex(keysDoc, options);
        } catch (Exception e) {
            throw new SeedLoadingException("Failed to ensure indexes for " + dataset.getCollection() + " (generic Mongo): " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void upsertRecord(SeedContext context, SeedPackManifest.Dataset dataset, Map<String, Object> record) {
        Optional<BaseMorphiaRepo<? extends UnversionedBaseModel>> repoOpt = resolveRepo(dataset);

        // Ensure collection is set based on either repo or modelClass hint
        ensureCollectionSet(dataset, repoOpt);

        if (repoOpt.isPresent()) {
            BaseMorphiaRepo<? extends UnversionedBaseModel> repo = repoOpt.get();
            Class<? extends UnversionedBaseModel> modelClass = repo.getPersistentClass();
            // Convert record map to model instance (sanitize and adapt known fields)
            Map<String, Object> adapted = adaptForModel(record, modelClass);
            @SuppressWarnings("unchecked")
            UnversionedBaseModel entity = (UnversionedBaseModel) objectMapper.convertValue(adapted, modelClass);

            // Implement upsert semantics based on dataset.naturalKey
            List<String> naturalKey = dataset.getNaturalKey();
            if (naturalKey == null || naturalKey.isEmpty()) {
                @SuppressWarnings("unchecked")
                BaseMorphiaRepo<UnversionedBaseModel> typedRepo = (BaseMorphiaRepo<UnversionedBaseModel>) repo;
                typedRepo.save(context.getRealm(), entity);
                return;
            }

            Optional<UnversionedBaseModel> existing = findExistingByNaturalKey(
                    repo, context.getRealm(), modelClass, record, naturalKey);

            if (existing.isPresent()) {
                if (!dataset.isUpsert()) {
                    Log.debugf("Skipping insert-only record for %s (natural key already exists)", dataset.getCollection());
                    return;
                }
                // Update existing entity
                entity.setId(existing.get().getId());
            }

            @SuppressWarnings("unchecked")
            BaseMorphiaRepo<UnversionedBaseModel> typedRepo = (BaseMorphiaRepo<UnversionedBaseModel>) repo;
            typedRepo.save(context.getRealm(), entity);
            return;
        }

        // Fallback path: no modelClass => use generic Mongo collection operations
        MongoDatabase database = mongoClient.getDatabase(context.getRealm());
        MongoCollection<org.bson.Document> collection = database.getCollection(dataset.getCollection());
        org.bson.Document document = new org.bson.Document();
        // Copy fields preserving order
        for (Map.Entry<String, Object> e : record.entrySet()) {
            document.append(e.getKey(), convertScalar(e.getValue()));
        }

        List<String> naturalKey = dataset.getNaturalKey();
        if (naturalKey == null || naturalKey.isEmpty()) {
            // No natural key => insert or replace by _id if provided
            if (document.containsKey("_id")) {
                org.bson.Document filter = new org.bson.Document("_id", document.get("_id"));
                collection.replaceOne(filter, document, new ReplaceOptions().upsert(true));
            } else {
                collection.insertOne(document);
            }
            return;
        }

        org.bson.Document filter = new org.bson.Document();
        for (String key : naturalKey) {
            Object v = document.get(key);
            if (v == null) {
                // Cannot match; in insert-only mode skip; in upsert mode insert
                if (dataset.isUpsert()) {
                    collection.insertOne(document);
                }
                return;
            }
            filter.append(key, v);
        }

        if (dataset.isUpsert()) {
            collection.replaceOne(filter, document, new ReplaceOptions().upsert(true));
        } else {
            if (collection.find(filter).limit(1).first() == null) {
                collection.insertOne(document);
            }
        }
    }

    /**
     * Finds an existing entity by natural key using a database query.
     * This is much more efficient than loading all entities into memory.
     *
     * @param repo the repository to use
     * @param realm the realm identifier
     * @param modelClass the model class
     * @param record the record containing natural key values
     * @param naturalKey the list of natural key field names
     * @return Optional containing the existing entity if found
     */
    @SuppressWarnings("unchecked")
    private Optional<UnversionedBaseModel> findExistingByNaturalKey(
            BaseMorphiaRepo<? extends UnversionedBaseModel> repo,
            String realm,
            Class<? extends UnversionedBaseModel> modelClass,
            Map<String, Object> record,
            List<String> naturalKey) {

        // Build filters for natural key fields
        List<Filter> filters = new ArrayList<>();
        for (String key : naturalKey) {
            Object value = record.get(key);
            if (value == null) {
                // If any natural key field is null, we can't match
                return Optional.empty();
            }
            filters.add(Filters.eq(key, value));
        }

        // Use datastore directly to query (bypassing security rules for seed operations)
        // Seeds run with system/admin context, so this is safe
        Datastore datastore = morphiaDataStore.getDataStore(realm);
        Query<? extends UnversionedBaseModel> query = datastore.find(modelClass)
                .filter(filters.toArray(new Filter[0]));

        UnversionedBaseModel existing = query.first();
        return Optional.ofNullable(existing);
    }


    private Map<String, Object> adaptForModel(Map<String, Object> record, Class<? extends UnversionedBaseModel> modelClass) {
        Map<String, Object> adapted = new LinkedHashMap<>(record);
        // Remove realmId which is added by tenantSubstitution transform and not part of the model
        adapted.remove("realmId");
        // If model has dataDomain field and top-level tenant context fields exist, move them under dataDomain
        if (findField(modelClass, "dataDomain") != null) {
            boolean hasAny = adapted.containsKey("tenantId") || adapted.containsKey("orgRefName")
                    || adapted.containsKey("ownerId") || adapted.containsKey("accountNum") || adapted.containsKey("dataSegment");
            if (hasAny) {
                Map<String, Object> dd = new LinkedHashMap<>();
                putIfHas(adapted, dd, "orgRefName");
                putIfHas(adapted, dd, "accountNum");
                putIfHas(adapted, dd, "tenantId");
                putIfHas(adapted, dd, "ownerId");
                // dataSegment optional numeric
                if (adapted.containsKey("dataSegment")) {
                    dd.put("dataSegment", adapted.remove("dataSegment"));
                }
                // If target already has dataDomain map, merge (explicit values win)
                Object currentDD = adapted.get("dataDomain");
                if (currentDD instanceof Map<?, ?> m) {
                    Map<String, Object> merged = new LinkedHashMap<>();
                    m.forEach((k, v) -> merged.put(String.valueOf(k), v));
                    merged.putAll(dd);
                    adapted.put("dataDomain", merged);
                } else {
                    adapted.put("dataDomain", dd);
                }
            }
        }
        return adapted;
    }

    private void putIfHas(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.remove(key));
        }
    }

    private java.lang.reflect.Field findField(Class<?> type, String name) {
        Class<?> t = type;
        while (t != null && t != Object.class) {
            try {
                return t.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
            }
            t = t.getSuperclass();
        }
        return null;
    }

    private Optional<BaseMorphiaRepo<? extends UnversionedBaseModel>> resolveRepo(SeedPackManifest.Dataset dataset) {
        String modelClassName = null;
        try {
            var getter = SeedPackManifest.Dataset.class.getDeclaredMethod("getModelClass");
            Object val = getter.invoke(dataset);
            modelClassName = (val instanceof String s) ? s : null;
        } catch (Exception ignored) {
        }
        if (modelClassName == null || modelClassName.isBlank()) {
            return Optional.empty();
        }
        try {
            Class<?> cls = Class.forName(modelClassName);
            if (!UnversionedBaseModel.class.isAssignableFrom(cls)) {
                throw new IllegalStateException("Configured modelClass does not extend UnversionedBaseModel: " + modelClassName);
            }
            Class<? extends UnversionedBaseModel> modelClass = (Class<? extends UnversionedBaseModel>) cls;
            for (BaseMorphiaRepo<? extends UnversionedBaseModel> repo : allRepos) {
                try {
                    if (repo.getPersistentClass().equals(modelClass)) {
                        return Optional.of(repo);
                    }
                } catch (Exception e) {
                    // continue
                }
            }
            throw new IllegalStateException("No Morphia repository bean found for model class: " + modelClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Model class not found: " + modelClassName, e);
        }
    }

    // Minimal value conversion similar to MongoSeedRepository
    @SuppressWarnings("unchecked")
    private Object convertScalar(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((k, v) -> nested.put(String.valueOf(k), convertScalar(v)));
            return new org.bson.Document(nested);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::convertScalar).toList();
        }
        // Convert string ObjectId like values into ObjectId if appropriate
        if (value instanceof String s) {
            // simple heuristic: 24-hex string
            if (s.length() == 24) {
                try {
                    return new ObjectId(s);
                } catch (Exception ignore) {
                }
            }
        }
        return value;
    }

    private void ensureCollectionSet(SeedPackManifest.Dataset dataset,
                                     Optional<BaseMorphiaRepo<? extends UnversionedBaseModel>> repoOpt) {
        if (dataset.getCollection() != null && !dataset.getCollection().isBlank()) {
            return;
        }
        try {
            if (repoOpt.isPresent()) {
                Class<? extends UnversionedBaseModel> cls = repoOpt.get().getPersistentClass();
                try {
                    dev.morphia.annotations.Entity ann = cls.getAnnotation(dev.morphia.annotations.Entity.class);
                    if (ann != null && ann.value() != null && !ann.value().isBlank()) {
                        dataset.setCollection(ann.value());
                        return;
                    }
                } catch (Throwable ignore) {
                }
                dataset.setCollection(cls.getSimpleName());
                return;
            }
        } catch (Exception ignore) {
        }
        // Fallback: attempt reading modelClass and use simple name
        try {
            var getter = SeedPackManifest.Dataset.class.getDeclaredMethod("getModelClass");
            Object val = getter.invoke(dataset);
            String mc = (val instanceof String s) ? s : null;
            if (mc != null && !mc.isBlank()) {
                Class<?> cls2 = Class.forName(mc);
                dataset.setCollection(cls2.getSimpleName());
            }
        } catch (Exception ignored) {
        }
    }
}
