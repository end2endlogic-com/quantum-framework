package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.model.OntologyMeta;
import com.mongodb.client.model.ReturnDocument;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Date;
import java.util.Optional;

/**
 * Repository for OntologyMeta singleton (global, not per-realm).
 * Uses findAndModify with upsert=true to avoid race conditions.
 */
@ApplicationScoped
public class OntologyMetaRepo extends MorphiaRepo<OntologyMeta> {

    public Optional<OntologyMeta> getSingleton() {
        return Optional.ofNullable(ds().find(OntologyMeta.class)
                .filter(Filters.eq("refName", "global"))
                .first());
    }

    /**
     * Record an observation of the current ontology YAML state without changing applied hashes.
     * Uses findAndModify with upsert=true to avoid races.
     */
    public OntologyMeta upsertObservation(Integer yamlVersion, String source, boolean reindexRequired) {
        Date now = new Date();
        dev.morphia.ModifyOptions opts = new dev.morphia.ModifyOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER);
        return ds().find(OntologyMeta.class)
                .filter(Filters.eq("refName", "global"))
                .modify(opts, UpdateOperators.set("yamlVersion", yamlVersion),
                        UpdateOperators.set("source", source),
                        UpdateOperators.set("updatedAt", now),
                        UpdateOperators.set("reindexRequired", reindexRequired));
    }

    /**
     * Mark that the current YAML has been applied: set yamlHash, tboxHash, yamlVersion, appliedAt and clear reindexRequired.
     * Uses findAndModify with upsert=true to avoid races.
     */
    public OntologyMeta markApplied(String yamlHash, String tboxHash, Integer yamlVersion) {
        Date now = new Date();
        dev.morphia.ModifyOptions opts = new dev.morphia.ModifyOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER);
        return ds().find(OntologyMeta.class)
                .filter(Filters.eq("refName", "global"))
                .modify(opts, UpdateOperators.set("yamlHash", yamlHash),
                        UpdateOperators.set("tboxHash", tboxHash),
                        UpdateOperators.set("yamlVersion", yamlVersion),
                        UpdateOperators.set("appliedAt", now),
                        UpdateOperators.set("updatedAt", now),
                        UpdateOperators.set("reindexRequired", false));
    }

    private dev.morphia.Datastore ds() {
        return morphiaDataStoreWrapper.getDataStore(defaultRealm);
    }
}
