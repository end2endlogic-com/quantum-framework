package com.e2eq.framework.imports.service;

import com.e2eq.framework.model.persistent.imports.LookupConfig;
import com.e2eq.framework.model.persistent.imports.LookupFailBehavior;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.model.persistent.base.BaseModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.arc.All;
import org.jboss.logging.Logger;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of LookupService.
 * Uses MorphiaRepo instances to perform cross-collection lookups with caching.
 */
@ApplicationScoped
public class LookupServiceImpl implements LookupService {

    private static final Logger LOG = Logger.getLogger(LookupServiceImpl.class);

    @Inject
    @All
    List<MorphiaRepo<?>> repos;

    // Cache: collection -> (matchField:value -> returnValue)
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    // Cache statistics
    private long cacheHits = 0;
    private long cacheMisses = 0;
    private long cacheEvictions = 0;

    @Override
    public Optional<Object> lookup(String csvValue, LookupConfig config, String realmId) {
        if (csvValue == null || csvValue.isEmpty()) {
            return handleNotFound(config, csvValue);
        }

        String collection = config.getLookupCollection();
        String matchField = config.getLookupMatchField();
        String returnField = config.getLookupReturnField();

        // Check cache first if caching is enabled
        if (config.isCacheLookups()) {
            String cacheKey = buildCacheKey(matchField, csvValue);
            Map<String, Object> collectionCache = cache.get(collection);
            if (collectionCache != null && collectionCache.containsKey(cacheKey)) {
                cacheHits++;
                Object cached = collectionCache.get(cacheKey);
                // Handle cached "not found" marker
                if (cached == NOT_FOUND_MARKER) {
                    return handleNotFound(config, csvValue);
                }
                return Optional.of(cached);
            }
            cacheMisses++;
        }

        // Find the appropriate repo
        MorphiaRepo<?> repo = findRepoForCollection(collection);
        if (repo == null) {
            LOG.warnf("No repository found for collection: %s", collection);
            return handleNotFound(config, csvValue);
        }

        // Build query
        String query = buildQuery(matchField, csvValue, config.getLookupFilter());

        try {
            // Execute query
            List<?> results = repo.getListByQuery(realmId, 0, 1, query, null, null);

            if (results.isEmpty()) {
                // Cache the "not found" result
                if (config.isCacheLookups()) {
                    cacheResult(collection, matchField, csvValue, NOT_FOUND_MARKER);
                }
                return handleNotFound(config, csvValue);
            }

            // Extract the return field value
            Object result = results.get(0);
            Object returnValue = extractFieldValue(result, returnField);

            // Cache the result
            if (config.isCacheLookups() && returnValue != null) {
                cacheResult(collection, matchField, csvValue, returnValue);
            }

            return Optional.ofNullable(returnValue);

        } catch (Exception e) {
            LOG.errorf(e, "Lookup failed for collection %s, field %s, value %s",
                    collection, matchField, csvValue);
            return handleNotFound(config, csvValue);
        }
    }

    @Override
    public void clearCache() {
        int size = cache.values().stream().mapToInt(Map::size).sum();
        cacheEvictions += size;
        cache.clear();
        LOG.debugf("Cleared lookup cache, evicted %d entries", size);
    }

    @Override
    public void clearCache(String collection) {
        Map<String, Object> collectionCache = cache.remove(collection);
        if (collectionCache != null) {
            cacheEvictions += collectionCache.size();
            LOG.debugf("Cleared lookup cache for collection %s, evicted %d entries",
                    collection, collectionCache.size());
        }
    }

    @Override
    public LookupCacheStats getCacheStats() {
        int totalSize = cache.values().stream().mapToInt(Map::size).sum();
        return new LookupCacheStats(cacheHits, cacheMisses, cacheEvictions, totalSize);
    }

    // Marker object for caching "not found" results
    private static final Object NOT_FOUND_MARKER = new Object();

    private String buildCacheKey(String matchField, String value) {
        return matchField + ":" + value;
    }

    private void cacheResult(String collection, String matchField, String csvValue, Object result) {
        cache.computeIfAbsent(collection, k -> new ConcurrentHashMap<>())
                .put(buildCacheKey(matchField, csvValue), result);
    }

    private Optional<Object> handleNotFound(LookupConfig config, String csvValue) {
        LookupFailBehavior behavior = config.getOnNotFound();
        if (behavior == null) {
            behavior = LookupFailBehavior.FAIL;
        }

        switch (behavior) {
            case NULL:
                return Optional.empty();
            case PASSTHROUGH:
                return Optional.ofNullable(csvValue);
            case FAIL:
            default:
                // Return empty - caller should handle as error
                return Optional.empty();
        }
    }

    private MorphiaRepo<?> findRepoForCollection(String collection) {
        if (repos == null || collection == null) {
            return null;
        }

        // Try to match by entity class name
        for (MorphiaRepo<?> repo : repos) {
            try {
                Class<?> entityClass = repo.getPersistentClass();
                if (entityClass != null) {
                    // Match by simple name or full name
                    if (entityClass.getSimpleName().equalsIgnoreCase(collection) ||
                            entityClass.getName().equals(collection)) {
                        return repo;
                    }
                }
            } catch (Exception e) {
                // Ignore and continue
            }
        }

        return null;
    }

    private String buildQuery(String matchField, String value, String additionalFilter) {
        // Build BIAPI query syntax
        StringBuilder query = new StringBuilder();
        query.append(matchField).append("='").append(escapeQueryValue(value)).append("'");

        if (additionalFilter != null && !additionalFilter.isEmpty()) {
            query.append(" and (").append(additionalFilter).append(")");
        }

        return query.toString();
    }

    private String escapeQueryValue(String value) {
        if (value == null) {
            return "";
        }
        // Escape single quotes
        return value.replace("'", "\\'");
    }

    private Object extractFieldValue(Object bean, String fieldName) {
        if (bean == null || fieldName == null) {
            return null;
        }

        try {
            // Handle nested fields (e.g., "address.city")
            String[] parts = fieldName.split("\\.");
            Object current = bean;

            for (String part : parts) {
                if (current == null) {
                    return null;
                }
                current = getFieldValueDirect(current, part);
            }

            return current;
        } catch (Exception e) {
            LOG.warnf("Failed to extract field %s from %s: %s",
                    fieldName, bean.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private Object getFieldValueDirect(Object bean, String fieldName) throws Exception {
        Class<?> clazz = bean.getClass();

        // Try getter first
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
            return clazz.getMethod(getterName).invoke(bean);
        } catch (NoSuchMethodException e) {
            // Try boolean getter
            getterName = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            try {
                return clazz.getMethod(getterName).invoke(bean);
            } catch (NoSuchMethodException e2) {
                // Fall through to direct field access
            }
        }

        // Try direct field access
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(bean);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }

        throw new NoSuchFieldException("Field not found: " + fieldName);
    }
}
