package com.e2eq.framework.secrets.model;

import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ManagedSecretRepo extends com.e2eq.framework.model.persistent.morphia.MorphiaRepo<ManagedSecret> {

    public List<ManagedSecret> findAll(String realmId, String secretType) {
        var query = morphiaDataStoreWrapper.getDataStore(realmId).find(getPersistentClass());
        if (secretType != null && !secretType.isBlank()) {
            query = query.filter(Filters.eq("secretType", secretType));
        }
        return query.iterator().toList();
    }

    public Optional<ManagedSecret> findByRefName(String realmId, String refName) {
        if (refName == null || refName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                morphiaDataStoreWrapper.getDataStore(realmId)
                        .find(getPersistentClass())
                        .filter(Filters.eq("refName", refName))
                        .first()
        );
    }

    public boolean deleteByRefName(String realmId, String refName) {
        if (refName == null || refName.isBlank()) {
            return false;
        }
        long deleted = morphiaDataStoreWrapper.getDataStore(realmId)
                .find(getPersistentClass())
                .filter(Filters.eq("refName", refName))
                .delete()
                .getDeletedCount();
        return deleted > 0;
    }
}
