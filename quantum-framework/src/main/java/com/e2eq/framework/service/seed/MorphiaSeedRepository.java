package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.util.*;

/**
 * SeedRepository implementation that leverages Morphia repositories when a modelClass is provided
 * in the SeedPackManifest.Dataset. Falls back to MongoSeedRepository behavior when modelClass
 * is not specified (for backward compatibility with existing seed packs).
 */
@ApplicationScoped
public class MorphiaSeedRepository implements SeedRepository {

    @Inject
    Instance<BaseMorphiaRepo<? extends UnversionedBaseModel>> allRepos;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    com.mongodb.client.MongoClient mongoClient; // for fallback path

    private volatile MongoSeedRepository fallback; // lazily initialized

    private MongoSeedRepository fallback() {
        if (fallback == null) {
            synchronized (this) {
                if (fallback == null) {
                    fallback = new MongoSeedRepository(mongoClient);
                }
            }
        }
        return fallback;
    }

    @Override
    public void ensureIndexes(SeedContext context, SeedPackManifest.Dataset dataset, SeedPackManifest.Index index) {
        Optional<BaseMorphiaRepo<? extends UnversionedBaseModel>> repoOpt = resolveRepo(dataset);
        ensureCollectionSet(dataset, repoOpt);
        if (repoOpt.isPresent()) {
            try {
                // Let Morphia ensure mapped indexes for the model in the target realm
                repoOpt.get().ensureIndexes(context.getRealmId(), dataset.getCollection());
            } catch (Exception e) {
                Log.warnf("Failed ensuring indexes via Morphia for %s: %s", dataset.getCollection(), e.getMessage());
            }
            // Note: explicit index definitions in dataset are primarily for Mongo fallback; Morphia
            // ensures indexes based on model annotations.
        } else {
            // No modelClass provided; use legacy Mongo repository behavior
            fallback().ensureIndexes(context, dataset, index);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void upsertRecord(SeedContext context, SeedPackManifest.Dataset dataset, Map<String, Object> record) {
        Optional<BaseMorphiaRepo<? extends UnversionedBaseModel>> repoOpt = resolveRepo(dataset);
        ensureCollectionSet(dataset, repoOpt);
        if (repoOpt.isEmpty()) {
            // Backward compatibility
            fallback().upsertRecord(context, dataset, record);
            return;
        }
        BaseMorphiaRepo repo = (BaseMorphiaRepo) repoOpt.get();
        Class<? extends UnversionedBaseModel> modelClass = repo.getPersistentClass();
        // Convert record map to model instance (sanitize and adapt known fields)
        Map<String, Object> adapted = adaptForModel(record, modelClass);
        UnversionedBaseModel entity = (UnversionedBaseModel) objectMapper.convertValue(adapted, modelClass);

        // Implement upsert semantics based on dataset.naturalKey
        List<String> naturalKey = dataset.getNaturalKey();
        if (naturalKey == null || naturalKey.isEmpty()) {
            // If no natural key, just save (Morphia Repo applies security/data domain/audit)
            repo.save(context.getRealmId(), entity);
            return;
        }

        // Find existing entity by matching natural key fields. For simplicity and to avoid
        // depending on the repo's query language here, load a small list and filter in-memory.
        // Seed datasets are typically small.
        List existingList = repo.getAllList(context.getRealmId());
        UnversionedBaseModel matched = null;
        for (Object o : existingList) {
            if (o instanceof UnversionedBaseModel e) {
                if (matchesNaturalKey(e, record, naturalKey)) {
                    matched = e;
                    break;
                }
            }
        }
        if (matched != null) {
            entity.setId(matched.getId());
        }

        // Save will insert or replace based on presence of id
        try {
            repo.save(context.getRealmId(), entity);
        } catch (RuntimeException ex) {
            Log.warnf("Morphia save failed for model %s in realm %s: %s. Falling back to Mongo write.",
                    modelClass.getSimpleName(), context.getRealmId(), ex.getMessage());
            // Fallback to direct Mongo write using original record (already adapted but may miss Morphia-only fields)
            fallback().upsertRecord(context, dataset, record);
        }
    }

    private boolean matchesNaturalKey(UnversionedBaseModel entity, Map<String, Object> record, List<String> keys) {
        for (String key : keys) {
            Object rv = record.get(key);
            Object ev = readProperty(entity, key);
            if (!Objects.equals(normalizeValue(ev), normalizeValue(rv))) {
                return false;
            }
        }
        return true;
    }

    private Object normalizeValue(Object v) {
        if (v instanceof ObjectId oid) {
            return oid.toHexString();
        }
        return v;
    }

    private Object readProperty(UnversionedBaseModel entity, String key) {
        try {
            var fld = findField(entity.getClass(), key);
            if (fld == null) return null;
            fld.setAccessible(true);
            return fld.get(entity);
        } catch (Exception e) {
            return null;
        }
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
