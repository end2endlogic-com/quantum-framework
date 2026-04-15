package com.e2eq.framework.service.seed;

import com.e2eq.framework.annotations.TrackReferences;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;
import com.e2eq.framework.model.persistent.base.ReferenceTarget;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
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

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

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
            Map<String, Object> adapted = adaptForModel(record, dataset, modelClass, context);
            @SuppressWarnings("unchecked")
            UnversionedBaseModel entity = (UnversionedBaseModel) objectMapper.convertValue(adapted, modelClass);
            hydrateResolvedModelReferences(adapted, dataset, modelClass, entity);

            // Implement upsert semantics based on dataset.naturalKey
            List<String> naturalKey = effectiveNaturalKey(dataset, modelClass, record);
            if (naturalKey == null || naturalKey.isEmpty()) {
                @SuppressWarnings("unchecked")
                BaseMorphiaRepo<UnversionedBaseModel> typedRepo = (BaseMorphiaRepo<UnversionedBaseModel>) repo;
                // No natural key at all => simple save
                UnversionedBaseModel saved = typedRepo.save(context.getRealm(), entity);
                syncTrackedReferences(context, dataset, modelClass, saved);
                return;
            }

            Optional<UnversionedBaseModel> existing = findExistingByNaturalKey(
                    repo, context.getRealm(), modelClass, record, naturalKey);
            if (existing.isEmpty() && entity.getId() != null) {
                existing = findExistingById(context.getRealm(), modelClass, entity.getId());
            }

            if (existing.isPresent()) {
                if (!dataset.isUpsert()) {
                    Log.debugf("Skipping insert-only record for %s (natural key already exists)", dataset.getCollection());
                    return;
                }
                // Merge changes INTO the existing entity and keep its id
                UnversionedBaseModel current = existing.get();
                ObjectId currentId = current.getId();
                try {
                    // Apply/merge provided fields onto the current entity
                    objectMapper.updateValue(current, adapted);
                    hydrateResolvedModelReferences(adapted, dataset, modelClass, current);
                    current.setId(currentId);
                } catch (IllegalArgumentException | JsonMappingException e) {
                    // Fall back to id-carry-over if merge fails for any reason
                    entity.setId(currentId);
                    current = entity;
                }

                @SuppressWarnings("unchecked")
                BaseMorphiaRepo<UnversionedBaseModel> typedRepo = (BaseMorphiaRepo<UnversionedBaseModel>) repo;
                UnversionedBaseModel saved = typedRepo.save(context.getRealm(), current);
                syncTrackedReferences(context, dataset, modelClass, saved);
                return;
            }

            @SuppressWarnings("unchecked")
            BaseMorphiaRepo<UnversionedBaseModel> typedRepo = (BaseMorphiaRepo<UnversionedBaseModel>) repo;
            UnversionedBaseModel saved = typedRepo.save(context.getRealm(), entity);
            syncTrackedReferences(context, dataset, modelClass, saved);
            return;
        } else {
           Log.warnf("!!! Could not resolve repo for dataset:%s modelClass:%s", dataset.getFile(), dataset.getModelClass());
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
            // Try default upsert by refName when present
            Object refName = document.get("refName");
            if (refName != null) {
                org.bson.Document filter = new org.bson.Document("refName", refName);
                collection.replaceOne(filter, document, new ReplaceOptions().upsert(true));
                return;
            }
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
     * Determine the effective natural key to use for upsert operations.
     * If the dataset explicitly defines a naturalKey, use it; otherwise,
     * default to ["refName"] when the model has a refName field or the record provides it.
     */
    private List<String> effectiveNaturalKey(SeedPackManifest.Dataset dataset,
                                             Class<? extends UnversionedBaseModel> modelClass,
                                             Map<String, Object> record) {
        List<String> nk = dataset.getNaturalKey();
        if (nk != null && !nk.isEmpty()) {
            return nk;
        }
        // Default: upsert by refName when present on model or record
        if (findField(modelClass, "refName") != null || record.containsKey("refName")) {
            return Collections.singletonList("refName");
        }
        return Collections.emptyList();
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
        Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
        Query<? extends UnversionedBaseModel> query = datastore.find(modelClass)
                .filter(filters.toArray(new Filter[0]));

        UnversionedBaseModel existing = query.first();
        return Optional.ofNullable(existing);
    }

    private Optional<UnversionedBaseModel> findExistingById(String realm,
                                                            Class<? extends UnversionedBaseModel> modelClass,
                                                            ObjectId id) {
        Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
        UnversionedBaseModel existing = datastore.find(modelClass)
                .filter(Filters.eq("_id", id))
                .first();
        return Optional.ofNullable(existing);
    }


    private Map<String, Object> adaptForModel(Map<String, Object> record,
                                              SeedPackManifest.Dataset dataset,
                                              Class<? extends UnversionedBaseModel> modelClass,
                                              SeedContext context) {
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

        resolveDeclaredReferences(adapted, dataset, modelClass, context);
        resolveParentReference(adapted, modelClass, context);
        return adapted;
    }

    private void syncTrackedReferences(SeedContext context,
                                       SeedPackManifest.Dataset dataset,
                                       Class<? extends UnversionedBaseModel> modelClass,
                                       UnversionedBaseModel saved) {
        if (saved == null || saved.getId() == null) {
            return;
        }

        Map<String, SeedPackManifest.ReferenceBinding> bindingsByField = new LinkedHashMap<>();
        for (SeedPackManifest.ReferenceBinding binding : SeedReferenceBindings.resolveBindings(dataset)) {
            bindingsByField.put(binding.getField(), binding);
        }

        Datastore datastore = morphiaDataStoreWrapper.getDataStore(context.getRealm());
        ReferenceEntry entry = new ReferenceEntry(saved.getId(), saved.getClass().getTypeName(), saved.getRefName());
        for (Field field : getAllFields(modelClass)) {
            if (field.getAnnotation(TrackReferences.class) == null) {
                continue;
            }
            SeedPackManifest.ReferenceBinding binding = bindingsByField.get(field.getName());
            Object rawValue = readFieldValue(field, saved);
            if (rawValue == null) {
                continue;
            }
            for (UnversionedBaseModel target : trackedReferenceTargets(context, binding, field, rawValue)) {
                if (target == null || target.getId() == null || target.getId().equals(saved.getId())) {
                    continue;
                }
                Set<ReferenceEntry> references = target.getReferences();
                if (references == null) {
                    references = new LinkedHashSet<>();
                    target.setReferences(references);
                }
                if (references.add(entry)) {
                    datastore.save(target);
                }
            }
        }
    }

    private List<UnversionedBaseModel> trackedReferenceTargets(SeedContext context,
                                                               SeedPackManifest.ReferenceBinding binding,
                                                               Field field,
                                                               Object rawValue) {
        if (rawValue instanceof Collection<?> values) {
            List<UnversionedBaseModel> targets = new ArrayList<>(values.size());
            for (Object value : values) {
                UnversionedBaseModel target = trackedReferenceTarget(context, binding, field, value);
                if (target != null) {
                    targets.add(target);
                }
            }
            return targets;
        }

        UnversionedBaseModel target = trackedReferenceTarget(context, binding, field, rawValue);
        return target == null ? List.of() : List.of(target);
    }

    private UnversionedBaseModel trackedReferenceTarget(SeedContext context,
                                                        SeedPackManifest.ReferenceBinding binding,
                                                        Field field,
                                                        Object rawValue) {
        if (rawValue instanceof UnversionedBaseModel model) {
            if (model.getId() == null) {
                return null;
            }
            Datastore datastore = morphiaDataStoreWrapper.getDataStore(context.getRealm());
            @SuppressWarnings("unchecked")
            Class<? extends UnversionedBaseModel> targetClass = (Class<? extends UnversionedBaseModel>) model.getClass();
            return datastore.find(targetClass).filter(Filters.eq("_id", model.getId())).first();
        }

        if (rawValue instanceof EntityReference entityReference) {
            if (binding == null) {
                binding = bindingFromReferenceTarget(field);
            }
            Class<? extends UnversionedBaseModel> targetClass = resolveTargetModelClass(binding, field);
            Datastore datastore = morphiaDataStoreWrapper.getDataStore(context.getRealm());
            if (entityReference.getEntityId() != null) {
                return datastore.find(targetClass).filter(Filters.eq("_id", entityReference.getEntityId())).first();
            }
            if (entityReference.getEntityRefName() != null && !entityReference.getEntityRefName().isBlank()) {
                return findReferencedEntity(context.getRealm(), targetClass, "refName", entityReference.getEntityRefName());
            }
        }

        return null;
    }

    private SeedPackManifest.ReferenceBinding bindingFromReferenceTarget(Field field) {
        ReferenceTarget referenceTarget = field.getAnnotation(ReferenceTarget.class);
        if (referenceTarget == null) {
            throw new SeedLoadingException("Tracked EntityReference field '" + field.getName() + "' requires targetModelClass or a @ReferenceTarget annotation");
        }
        SeedPackManifest.ReferenceBinding binding = new SeedPackManifest.ReferenceBinding();
        binding.setField(field.getName());
        binding.setTargetModelClass(referenceTarget.target().getName());
        binding.setTargetCollection(referenceTarget.collection());
        return binding;
    }

    private Object readFieldValue(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new SeedLoadingException("Unable to read tracked reference field '" + field.getName() + "'", e);
        }
    }

    private void hydrateResolvedModelReferences(Map<String, Object> adapted,
                                                SeedPackManifest.Dataset dataset,
                                                Class<? extends UnversionedBaseModel> modelClass,
                                                UnversionedBaseModel entity) {
        for (SeedPackManifest.ReferenceBinding binding : SeedReferenceBindings.resolveBindings(dataset)) {
            Object resolved = adapted.get(binding.getField());
            if (resolved == null) {
                continue;
            }
            Field field = findField(modelClass, binding.getField());
            if (field == null) {
                continue;
            }
            if (resolved instanceof UnversionedBaseModel || isResolvedModelCollection(resolved)) {
                writeFieldValue(field, entity, resolved);
            }
        }
    }

    private boolean isResolvedModelCollection(Object value) {
        if (!(value instanceof Collection<?> values) || values.isEmpty()) {
            return false;
        }
        for (Object element : values) {
            if (!(element instanceof UnversionedBaseModel)) {
                return false;
            }
        }
        return true;
    }

    private void writeFieldValue(Field field, Object target, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new SeedLoadingException("Unable to write resolved reference field '" + field.getName() + "'", e);
        }
    }

    private void resolveDeclaredReferences(Map<String, Object> adapted,
                                           SeedPackManifest.Dataset dataset,
                                           Class<? extends UnversionedBaseModel> modelClass,
                                           SeedContext context) {
        for (SeedPackManifest.ReferenceBinding binding : SeedReferenceBindings.resolveBindings(dataset)) {
            Field field = findField(modelClass, binding.getField());
            if (field == null) {
                throw new SeedLoadingException("Reference field '" + binding.getField() + "' was not found on model " + modelClass.getName());
            }

            Object rawValue = adapted.get(binding.getField());
            if (rawValue == null) {
                continue;
            }

            Object resolved = resolveFieldReferenceValue(binding, field, rawValue, context);
            adapted.put(binding.getField(), resolved);
        }
    }

    private Object resolveFieldReferenceValue(SeedPackManifest.ReferenceBinding binding,
                                              Field field,
                                              Object rawValue,
                                              SeedContext context) {
        Class<?> rawFieldType = field.getType();
        if (Collection.class.isAssignableFrom(rawFieldType)) {
            return resolveCollectionReferenceValue(binding, field, rawValue, context);
        }
        if (EntityReference.class.isAssignableFrom(rawFieldType)) {
            return resolveEntityReferenceValue(binding, field, rawValue, context);
        }
        if (UnversionedBaseModel.class.isAssignableFrom(rawFieldType)) {
            @SuppressWarnings("unchecked")
            Class<? extends UnversionedBaseModel> targetType = (Class<? extends UnversionedBaseModel>) rawFieldType;
            return resolveModelReferenceValue(binding, targetType, rawValue, context);
        }
        throw new SeedLoadingException("Reference field '" + binding.getField() + "' on " + field.getDeclaringClass().getName()
                + " is not a supported reference type");
    }

    private Object resolveCollectionReferenceValue(SeedPackManifest.ReferenceBinding binding,
                                                   Field field,
                                                   Object rawValue,
                                                   SeedContext context) {
        if (!(rawValue instanceof List<?> values)) {
            throw new SeedLoadingException("Reference field '" + binding.getField() + "' must be a list");
        }
        Class<?> elementType = resolveCollectionElementType(field);
        if (elementType == null) {
            throw new SeedLoadingException("Unable to resolve element type for reference field '" + binding.getField() + "'");
        }

        List<Object> resolved = new ArrayList<>(values.size());
        for (Object value : values) {
            if (EntityReference.class.isAssignableFrom(elementType)) {
                resolved.add(resolveEntityReferenceValue(binding, field, value, context));
                continue;
            }
            if (UnversionedBaseModel.class.isAssignableFrom(elementType)) {
                @SuppressWarnings("unchecked")
                Class<? extends UnversionedBaseModel> targetType = (Class<? extends UnversionedBaseModel>) elementType;
                resolved.add(resolveModelReferenceValue(binding, targetType, value, context));
                continue;
            }
            throw new SeedLoadingException("Reference field '" + binding.getField() + "' has unsupported collection element type " + elementType.getName());
        }
        return resolved;
    }

    private Object resolveEntityReferenceValue(SeedPackManifest.ReferenceBinding binding,
                                               Field field,
                                               Object rawValue,
                                               SeedContext context) {
        Map<String, Object> resolved = mutableReferenceMap(rawValue);
        ObjectId entityId = extractNestedObjectId(resolved, "entityId", "id", "_id");
        if (entityId == null) {
            String lookupValue = extractLookupValue(binding, resolved, rawValue, true);
            if (lookupValue == null || lookupValue.isBlank()) {
                if (binding.isRequired()) {
                    throw new SeedLoadingException("Reference field '" + binding.getField() + "' is missing lookup value '" + binding.getLookupBy() + "'");
                }
                return rawValue;
            }
            Class<? extends UnversionedBaseModel> targetClass = resolveTargetModelClass(binding, field);
            UnversionedBaseModel target = findReferencedEntity(context.getRealm(), targetClass, binding.getLookupBy(), lookupValue);
            if (target == null || target.getId() == null) {
                throw new SeedLoadingException("Unable to resolve EntityReference field '" + binding.getField()
                        + "' to " + targetClass.getName() + " using " + binding.getLookupBy() + "=" + lookupValue);
            }
            resolved.put("entityId", target.getId());
            resolved.putIfAbsent("entityRefName", target.getRefName());
            resolved.putIfAbsent("entityDisplayName", target.getDisplayName());
            resolved.putIfAbsent("entityType", target.getClass().getSimpleName());
        } else {
            resolved.put("entityId", entityId);
        }
        return resolved;
    }

    private Object resolveModelReferenceValue(SeedPackManifest.ReferenceBinding binding,
                                              Class<? extends UnversionedBaseModel> targetType,
                                              Object rawValue,
                                              SeedContext context) {
        Map<String, Object> resolved = mutableReferenceMap(rawValue);
        ObjectId id = extractNestedObjectId(resolved, "id", "_id", "entityId");
        if (id != null) {
            Datastore datastore = morphiaDataStoreWrapper.getDataStore(context.getRealm());
            UnversionedBaseModel target = datastore.find(targetType).filter(Filters.eq("_id", id)).first();
            if (target != null) {
                return target;
            }
            resolved.put("id", id);
            resolved.remove("_id");
            return resolved;
        }

        String lookupValue = extractLookupValue(binding, resolved, rawValue, false);
        if (lookupValue == null || lookupValue.isBlank()) {
            if (binding.isRequired()) {
                throw new SeedLoadingException("Reference field '" + binding.getField() + "' is missing lookup value '" + binding.getLookupBy() + "'");
            }
            return rawValue;
        }

        UnversionedBaseModel target = findReferencedEntity(context.getRealm(), targetType, binding.getLookupBy(), lookupValue);
        if (target == null || target.getId() == null) {
            throw new SeedLoadingException("Unable to resolve reference field '" + binding.getField()
                    + "' to " + targetType.getName() + " using " + binding.getLookupBy() + "=" + lookupValue);
        }

        return target;
    }

    private Map<String, Object> mutableReferenceMap(Object rawValue) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (rawValue instanceof Map<?, ?> map) {
            map.forEach((k, v) -> resolved.put(String.valueOf(k), v));
            return resolved;
        }
        if (rawValue instanceof String stringValue && !stringValue.isBlank()) {
            resolved.put("refName", stringValue);
            return resolved;
        }
        throw new SeedLoadingException("Unsupported reference payload: " + rawValue);
    }

    private ObjectId extractNestedObjectId(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object raw = values.get(key);
            if (raw instanceof ObjectId objectId) {
                return objectId;
            }
            if (raw instanceof String stringValue && !stringValue.isBlank()) {
                try {
                    return new ObjectId(stringValue);
                } catch (IllegalArgumentException ignore) {
                }
            }
        }
        return null;
    }

    private String extractLookupValue(SeedPackManifest.ReferenceBinding binding,
                                      Map<String, Object> resolved,
                                      Object rawValue,
                                      boolean preferEntityRefName) {
        Object lookup = resolved.get(binding.getLookupBy());
        if (lookup == null && preferEntityRefName) {
            lookup = resolved.get("entityRefName");
        }
        if (lookup == null) {
            lookup = resolved.get("refName");
        }
        if (lookup == null && rawValue instanceof String) {
            lookup = rawValue;
        }
        return lookup == null ? null : String.valueOf(lookup);
    }

    private Class<?> resolveCollectionElementType(Field field) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return null;
        }
        Type[] arguments = parameterizedType.getActualTypeArguments();
        if (arguments.length != 1) {
            return null;
        }
        Type candidate = arguments[0];
        if (candidate instanceof Class<?> clazz) {
            return clazz;
        }
        if (candidate instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    private Class<? extends UnversionedBaseModel> resolveTargetModelClass(SeedPackManifest.ReferenceBinding binding, Field field) {
        if (binding.getTargetModelClass() != null && !binding.getTargetModelClass().isBlank()) {
            try {
                Class<?> loaded = Class.forName(binding.getTargetModelClass());
                if (!UnversionedBaseModel.class.isAssignableFrom(loaded)) {
                    throw new SeedLoadingException("Reference target model class " + binding.getTargetModelClass() + " is not a UnversionedBaseModel");
                }
                @SuppressWarnings("unchecked")
                Class<? extends UnversionedBaseModel> cast = (Class<? extends UnversionedBaseModel>) loaded;
                return cast;
            } catch (ClassNotFoundException e) {
                throw new SeedLoadingException("Reference target model class not found: " + binding.getTargetModelClass(), e);
            }
        }

        ReferenceTarget referenceTarget = field.getAnnotation(ReferenceTarget.class);
        if (referenceTarget != null && UnversionedBaseModel.class.isAssignableFrom(referenceTarget.target())) {
            @SuppressWarnings("unchecked")
            Class<? extends UnversionedBaseModel> cast = (Class<? extends UnversionedBaseModel>) referenceTarget.target();
            return cast;
        }

        throw new SeedLoadingException("Reference field '" + binding.getField() + "' requires targetModelClass or a @ReferenceTarget annotation");
    }

    private UnversionedBaseModel findReferencedEntity(String realm,
                                                     Class<? extends UnversionedBaseModel> targetClass,
                                                     String lookupBy,
                                                     String lookupValue) {
        Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
        return datastore.find(targetClass)
                .filter(Filters.eq(lookupBy, lookupValue))
                .first();
    }

    private void resolveParentReference(Map<String, Object> adapted,
                                        Class<? extends UnversionedBaseModel> modelClass,
                                        SeedContext context) {
        if (findField(modelClass, "parent") == null) {
            return;
        }

        Object parent = adapted.get("parent");
        if (!(parent instanceof Map<?, ?> parentMap)) {
            return;
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        parentMap.forEach((k, v) -> resolved.put(String.valueOf(k), v));

        Object entityId = resolved.get("entityId");
        if (entityId instanceof String s && !s.isBlank()) {
            try {
                resolved.put("entityId", new ObjectId(s));
                adapted.put("parent", resolved);
                return;
            } catch (IllegalArgumentException ignore) {
                resolved.remove("entityId");
            }
        }
        if (entityId instanceof ObjectId) {
            adapted.put("parent", resolved);
            return;
        }

        Object entityRefName = resolved.get("entityRefName");
        if (!(entityRefName instanceof String refName) || refName.isBlank()) {
            adapted.put("parent", resolved);
            return;
        }

        Datastore datastore = morphiaDataStoreWrapper.getDataStore(context.getRealm());
        UnversionedBaseModel existingParent = datastore.find(modelClass)
                .filter(Filters.eq("refName", refName))
                .first();
        if (existingParent != null && existingParent.getId() != null) {
            resolved.put("entityId", existingParent.getId());
        }

        adapted.put("parent", resolved);
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

    private List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

   @SuppressWarnings("unchecked")
   private Optional<BaseMorphiaRepo<? extends UnversionedBaseModel>> resolveRepo(SeedPackManifest.Dataset dataset) {
      // 0) If repoClass is explicitly provided
      String repoClassName = null;
      try {
         repoClassName = dataset.getRepoClass();
      } catch (Throwable ignore) {
         try {
            var getter = SeedPackManifest.Dataset.class.getDeclaredMethod("getRepoClass");
            Object val = getter.invoke(dataset);
            repoClassName = (val instanceof String s) ? s : null;
         } catch (Exception ignored) {
         }
      }

      if (repoClassName != null && !repoClassName.isBlank()) {
         for (BaseMorphiaRepo<? extends UnversionedBaseModel> repo : allRepos) {
            // FIX: Get the real class behind the proxy
            Class<?> beanClass = getRealClass(repo);

            String simple = beanClass.getSimpleName();
            String fqn = beanClass.getName();

            if (repoClassName.equals(fqn) || repoClassName.equals(simple)) {
               return Optional.of(repo);
            }
         }
         throw new IllegalStateException("No Morphia repository bean found for repo class: " + repoClassName);
      }

      // 1) Try resolving by modelClass string (Method calls work fine on proxies)
      String modelClassName = null;
      try {
         var getter = SeedPackManifest.Dataset.class.getDeclaredMethod("getModelClass");
         Object val = getter.invoke(dataset);
         modelClassName = (val instanceof String s) ? s : null;
      } catch (Exception ignored) {
      }

      if (modelClassName != null && !modelClassName.isBlank()) {
         for (BaseMorphiaRepo<? extends UnversionedBaseModel> repo : allRepos) {
            try {
               // This works fine on proxies because it's a method call, not a reflection check on the repo itself
               Class<? extends UnversionedBaseModel> pc = repo.getPersistentClass();
               if (pc == null) continue;
               if (modelClassName.equals(pc.getName()) || modelClassName.equals(pc.getSimpleName())) {
                  return Optional.of(repo);
               }
            } catch (Throwable ignored) {
            }
         }
         throw new IllegalStateException("No Morphia repository bean found for model class: " + modelClassName);
      }

      // 2) If modelClass not provided, try resolving by collection name
      String collection = dataset.getCollection();
      if (collection != null && !collection.isBlank()) {
         String candidate1 = collection + "Repo";
         String candidate2 = java.beans.Introspector.decapitalize(candidate1);

         // [Arc lookup code remains the same...]

         // 2b) Best-effort: scan injected repos by simple bean class name
         for (BaseMorphiaRepo<? extends UnversionedBaseModel> repo : allRepos) {
            // FIX: Get the real class here as well
            Class<?> beanClass = getRealClass(repo);
            String simple = beanClass.getSimpleName();

            if (simple.equals(candidate1) || simple.equals(candidate2)
                   || simple.equalsIgnoreCase(candidate1) || simple.equalsIgnoreCase(candidate2)) {
               return Optional.of(repo);
            }
         }
      }

      return Optional.empty();
   }

   /**
    * Helper to unwrap Quarkus/CDI proxies to get the underlying class.
    */
   private Class<?> getRealClass(Object instance) {
      Class<?> clazz = instance.getClass();
      // Check if it's a Quarkus ClientProxy
      if (instance instanceof io.quarkus.arc.ClientProxy) {
         // Quarkus class-based proxies extend the original class
         return clazz.getSuperclass();
      }
      return clazz;
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
        if (dataset.getCollection() != null && !dataset.getCollection().isBlank() && !".".equals(dataset.getCollection())) {
            return;
        }
        try {
            if (repoOpt.isPresent()) {
                Class<? extends UnversionedBaseModel> cls = repoOpt.get().getPersistentClass();
                dataset.setCollection(SeedCollectionResolver.deriveCollectionName(cls.getName()));
                return;
            }
        } catch (Exception ignore) {
        }
        SeedCollectionResolver.ensureCollectionSet(dataset);
    }

    /**
     * Attempts to unwrap CDI/Arc proxy subclasses to the underlying bean class to make
     * name-based comparisons reliable. Quarkus typically generates proxies with suffixes like
     * _ClientProxy or _Subclass or _proxy. We walk up the superclass chain while the current
     * class name appears to be a proxy.
     */
    private static Class<?> effectiveBeanClass(Class<?> cls) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            String name = current.getName();
            if (isProxyName(name)) {
                Class<?> superCls = current.getSuperclass();
                if (superCls == null || superCls == Object.class) {
                    break;
                }
                current = superCls;
                continue;
            }
            break;
        }
        return current != null ? current : cls;
    }

    private static boolean isProxyName(String name) {
        if (name == null) return false;
        return name.endsWith("_proxy")
                || name.endsWith("_Proxy")
                || name.endsWith("_ClientProxy")
                || name.contains("$$")
                || name.endsWith("_Subclass");
    }
}
