package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing seed registry entries.
 * Tracks which seed datasets have been applied to which realms.
 */
@ApplicationScoped
public class SeedRegistryRepository extends MorphiaRepo<SeedRegistryEntry> {

    /**
     * Finds a registry entry by seed pack, version, dataset, and realm.
     * This method bypasses security rules since seed registry is system-level tracking.
     *
     * @param realm    the realm identifier
     * @param seedPack the seed pack name
     * @param version  the seed pack version
     * @param dataset  the dataset collection name
     * @return an Optional containing the entry if found
     */
    public Optional<SeedRegistryEntry> findEntry(String realm, String seedPack, String version, String dataset) {
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("seedPack", seedPack));
        filters.add(Filters.eq("version", version));
        filters.add(Filters.eq("dataset", dataset));
        filters.add(Filters.eq("appliedToRealm", realm));

        // Bypass security rules for seed registry (system-level tracking)
        Filter[] qfilters = filters.toArray(new Filter[0]);

        Query<SeedRegistryEntry> query = morphiaDataStoreWrapper.getDataStore(realm)
                .find(getPersistentClass())
                .filter(qfilters);

        SeedRegistryEntry entry = query.first();
        return Optional.ofNullable(entry);
    }
}
