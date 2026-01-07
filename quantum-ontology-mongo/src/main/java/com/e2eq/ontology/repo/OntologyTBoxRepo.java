package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.model.OntologyTBox;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class OntologyTBoxRepo extends MorphiaRepo<OntologyTBox> {

    /**
     * Delete all OntologyTBox documents in the current realm.
     */
    public void deleteAll() {
        ds().getCollection(OntologyTBox.class).deleteMany(new org.bson.Document());
    }

    /**
     * Get the most recently applied TBox
     */
    public Optional<OntologyTBox> findLatest() {
        Query<OntologyTBox> q = ds().find(OntologyTBox.class);
        FindOptions options = new FindOptions()
                .sort(Sort.descending("appliedAt"))
                .limit(1);
        List<OntologyTBox> results = q.iterator(options).toList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find TBox by hash
     */
    public Optional<OntologyTBox> findByHash(String tboxHash) {
        if (tboxHash == null || tboxHash.isBlank()) {
            return Optional.empty();
        }
        Query<OntologyTBox> q = ds().find(OntologyTBox.class)
                .filter(Filters.eq("tboxHash", tboxHash));
        return Optional.ofNullable(q.first());
    }

    /**
     * Find TBox by YAML hash (for correlation)
     */
    public Optional<OntologyTBox> findByYamlHash(String yamlHash) {
        if (yamlHash == null || yamlHash.isBlank()) {
            return Optional.empty();
        }
        Query<OntologyTBox> q = ds().find(OntologyTBox.class)
                .filter(Filters.eq("yamlHash", yamlHash));
        FindOptions options = new FindOptions()
                .sort(Sort.descending("appliedAt"))
                .limit(1);
        List<OntologyTBox> results = q.iterator(options).toList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Get the datastore for the current security context realm.
     * Uses getSecurityContextRealmId() to derive realm from security context,
     * falling back to defaultRealm if no security context is available.
     */
    private dev.morphia.Datastore ds() {
        return morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
    }

    /**
     * Get the datastore for a specific realm (used for explicit realm operations).
     */
    private dev.morphia.Datastore ds(String realm) {
        return morphiaDataStoreWrapper.getDataStore(realm);
    }
}
