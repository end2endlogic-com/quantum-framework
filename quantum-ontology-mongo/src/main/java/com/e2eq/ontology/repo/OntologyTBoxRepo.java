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
                .sort(Sort.descending("appliedAt"), Sort.descending("_id"))
                .limit(1);
        List<OntologyTBox> results = q.iterator(options).toList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * The TBox {@code builder} built most recently: the baseline of its next source build. Falls back
     * to the latest TBox recorded without a builder (persisted before builders were recorded). Ties
     * on {@code appliedAt} break on {@code _id}.
     */
    public Optional<OntologyTBox> findLatestForBuilder(String builder) {
        FindOptions options = new FindOptions()
                .sort(Sort.descending("appliedAt"), Sort.descending("_id"))
                .limit(1);
        List<OntologyTBox> own = ds().find(OntologyTBox.class)
                .filter(Filters.eq("builder", builder)).iterator(options).toList();
        if (!own.isEmpty()) return Optional.of(own.get(0));
        List<OntologyTBox> legacy = ds().find(OntologyTBox.class)
                .filter(Filters.exists("builder").not()).iterator(options).toList();
        return legacy.isEmpty() ? Optional.empty() : Optional.of(legacy.get(0));
    }

    /** The TBox with {@code tboxHash} that {@code builder} built, if it built one. */
    public Optional<OntologyTBox> findByHashAndBuilder(String tboxHash, String builder) {
        if (tboxHash == null || tboxHash.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ds().find(OntologyTBox.class)
                .filter(Filters.eq("tboxHash", tboxHash), Filters.eq("builder", builder)).first());
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
                .sort(Sort.descending("appliedAt"), Sort.descending("_id"))
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
