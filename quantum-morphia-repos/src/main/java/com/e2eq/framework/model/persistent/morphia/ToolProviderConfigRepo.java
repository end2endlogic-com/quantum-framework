package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.tools.ToolProviderConfig;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ToolProviderConfig per realm.
 */
@ApplicationScoped
public class ToolProviderConfigRepo {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    public ToolProviderConfig save(String realm, ToolProviderConfig config) {
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("realm is required");
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.save(config);
    }

    public Optional<ToolProviderConfig> findByRefName(String realm, String refName) {
        if (realm == null || refName == null || refName.isBlank()) {
            return Optional.empty();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        ToolProviderConfig c = ds.find(ToolProviderConfig.class)
            .filter(Filters.eq("refName", refName))
            .first();
        return Optional.ofNullable(c);
    }

    public Optional<ToolProviderConfig> findById(String realm, String id) {
        if (realm == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            ToolProviderConfig c = ds.find(ToolProviderConfig.class)
                .filter(Filters.eq("_id", new org.bson.types.ObjectId(id)))
                .first();
            return Optional.ofNullable(c);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public List<ToolProviderConfig> list(String realm) {
        if (realm == null || realm.isBlank()) {
            return List.of();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.find(ToolProviderConfig.class)
            .iterator()
            .toList()
            .stream()
            .sorted((a, b) -> (a.getRefName() != null ? a.getRefName() : "")
                .compareTo(b.getRefName() != null ? b.getRefName() : ""))
            .toList();
    }

    public boolean deleteById(String realm, String id) {
        if (realm == null || id == null || id.isBlank()) {
            return false;
        }
        try {
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            var result = ds.find(ToolProviderConfig.class)
                .filter(Filters.eq("_id", new org.bson.types.ObjectId(id)))
                .delete();
            return result.getDeletedCount() > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean deleteByRefName(String realm, String refName) {
        if (realm == null || refName == null || refName.isBlank()) {
            return false;
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        var result = ds.find(ToolProviderConfig.class)
            .filter(Filters.eq("refName", refName))
            .delete();
        return result.getDeletedCount() > 0;
    }
}
