package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ToolDefinition per realm. Tools are stored in realm-specific collections.
 */
@jakarta.enterprise.context.ApplicationScoped
public class ToolDefinitionRepo {

    @jakarta.inject.Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    /**
     * Saves a tool definition in the given realm.
     *
     * @param realm realm id
     * @param tool   tool to save
     * @return saved tool
     */
    public ToolDefinition save(String realm, ToolDefinition tool) {
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("realm is required");
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.save(tool);
    }

    /**
     * Finds a tool by refName within the realm.
     *
     * @param realm   realm id
     * @param refName tool ref name (e.g. query_find, query_save)
     * @return optional tool
     */
    public Optional<ToolDefinition> findByRefName(String realm, String refName) {
        if (realm == null || refName == null || refName.isBlank()) {
            return Optional.empty();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        ToolDefinition t = ds.find(ToolDefinition.class)
            .filter(Filters.eq("refName", refName))
            .first();
        return Optional.ofNullable(t);
    }

    /**
     * Finds a tool by id within the realm.
     *
     * @param realm realm id
     * @param id    tool id (ObjectId hex)
     * @return optional tool
     */
    public Optional<ToolDefinition> findById(String realm, String id) {
        if (realm == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            ToolDefinition t = ds.find(ToolDefinition.class)
                .filter(Filters.eq("_id", new org.bson.types.ObjectId(id)))
                .first();
            return Optional.ofNullable(t);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Lists all tools in the realm.
     *
     * @param realm realm id
     * @return list of tools (ordered by refName)
     */
    public List<ToolDefinition> list(String realm) {
        if (realm == null || realm.isBlank()) {
            return List.of();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.find(ToolDefinition.class)
            .iterator()
            .toList()
            .stream()
            .sorted((a, b) -> (a.getRefName() != null ? a.getRefName() : "")
                .compareTo(b.getRefName() != null ? b.getRefName() : ""))
            .toList();
    }

    /**
     * Deletes a tool by id in the realm.
     *
     * @param realm realm id
     * @param id    tool id (ObjectId hex)
     * @return true if deleted
     */
    public boolean deleteById(String realm, String id) {
        if (realm == null || id == null || id.isBlank()) {
            return false;
        }
        try {
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            var result = ds.find(ToolDefinition.class)
                .filter(Filters.eq("_id", new org.bson.types.ObjectId(id)))
                .delete();
            return result.getDeletedCount() > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Counts tools in the realm (optionally only enabled).
     *
     * @param realm   realm id
     * @param enabled if non-null, filter by enabled status
     * @return count
     */
    public long count(String realm, Boolean enabled) {
        if (realm == null || realm.isBlank()) {
            return 0;
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        var q = ds.find(ToolDefinition.class);
        if (enabled != null) {
            q.filter(Filters.eq("enabled", enabled));
        }
        return q.count();
    }

    /**
     * Finds tools by ref names within the realm. Skips null/blank ref names.
     *
     * @param realm    realm id
     * @param refNames tool ref names (e.g. query_find, query_save)
     * @return list of tools found (order not guaranteed)
     */
    public List<ToolDefinition> findByRefNames(String realm, List<String> refNames) {
        if (realm == null || realm.isBlank() || refNames == null || refNames.isEmpty()) {
            return List.of();
        }
        List<String> valid = refNames.stream()
            .filter(r -> r != null && !r.isBlank())
            .distinct()
            .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.find(ToolDefinition.class)
            .filter(Filters.in("refName", valid))
            .iterator()
            .toList();
    }

    /**
     * Finds tools whose category is in the given list (e.g. quantum.crud).
     *
     * @param realm     realm id
     * @param categories category values to match
     * @return list of tools (order not guaranteed)
     */
    public List<ToolDefinition> findByCategoryIn(String realm, List<String> categories) {
        if (realm == null || realm.isBlank() || categories == null || categories.isEmpty()) {
            return List.of();
        }
        List<String> valid = categories.stream()
            .filter(c -> c != null && !c.isBlank())
            .distinct()
            .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.find(ToolDefinition.class)
            .filter(Filters.in("category", valid))
            .iterator()
            .toList();
    }

    /**
     * Finds tools that have at least one tag in the given list.
     *
     * @param realm realm id
     * @param tags  tag values to match
     * @return list of tools (order not guaranteed)
     */
    public List<ToolDefinition> findByTagsAny(String realm, List<String> tags) {
        if (realm == null || realm.isBlank() || tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<String> valid = tags.stream()
            .filter(t -> t != null && !t.isBlank())
            .distinct()
            .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.find(ToolDefinition.class)
            .filter(Filters.in("tags", valid))
            .iterator()
            .toList();
    }
}
