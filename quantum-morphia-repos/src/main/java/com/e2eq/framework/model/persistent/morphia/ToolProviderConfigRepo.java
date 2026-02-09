package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.tools.ToolProviderConfig;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ToolProviderConfig (external REST/MCP providers). Realm-scoped.
 */
@ApplicationScoped
public class ToolProviderConfigRepo {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    public ToolProviderConfig save(String realm, ToolProviderConfig config) {
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

    public List<ToolProviderConfig> findAll(String realm) {
        if (realm == null) {
            return List.of();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.find(ToolProviderConfig.class)
            .iterator(new FindOptions().sort(Sort.ascending("refName")))
            .toList();
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
