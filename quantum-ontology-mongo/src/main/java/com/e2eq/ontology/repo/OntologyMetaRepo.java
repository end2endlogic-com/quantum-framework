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
 * Repository for OntologyMeta.
 * <p>
 * OntologyMeta is now per-realm (one per database/tenant), not global.
 * Uses findAndModify with upsert=true to avoid race conditions.
 * The realm is derived from the current SecurityContext.
 * </p>
 */
@ApplicationScoped
public class OntologyMetaRepo extends MorphiaRepo<OntologyMeta> {

    public Optional<OntologyMeta> getSingleton() {
        return getSingleton(getSecurityContextRealmId());
    }

    public Optional<OntologyMeta> getSingleton(String realmId) {
        return Optional.ofNullable(ds(realmId).find(OntologyMeta.class)
                .filter(Filters.eq("refName", "global"))
                .first());
    }

    /**
     * Delete all OntologyMeta documents in the current realm.
     */
    public void deleteAll() {
        ds().getCollection(OntologyMeta.class).deleteMany(new org.bson.Document());
    }

    /**
     * Record an observation of the current ontology YAML state without changing applied hashes.
     * Uses findAndModify with upsert=true to avoid races.
     */
    public OntologyMeta upsertObservation(Integer yamlVersion, String source, boolean reindexRequired) {
        return upsertObservation(getSecurityContextRealmId(), yamlVersion, source, reindexRequired);
    }

    public OntologyMeta upsertObservation(String realmId, Integer yamlVersion, String source, boolean reindexRequired) {
        Date now = new Date();
        dev.morphia.ModifyOptions opts = new dev.morphia.ModifyOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER);
        return ds(realmId).find(OntologyMeta.class)
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
        return markApplied(getSecurityContextRealmId(), yamlHash, tboxHash, yamlVersion);
    }

    public OntologyMeta markApplied(String realmId, String yamlHash, String tboxHash, Integer yamlVersion) {
        Date now = new Date();
        dev.morphia.ModifyOptions opts = new dev.morphia.ModifyOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER);
        return ds(realmId).find(OntologyMeta.class)
                .filter(Filters.eq("refName", "global"))
                .modify(opts, UpdateOperators.set("yamlHash", yamlHash),
                        UpdateOperators.set("tboxHash", tboxHash),
                        UpdateOperators.set("yamlVersion", yamlVersion),
                        UpdateOperators.set("appliedAt", now),
                        UpdateOperators.set("updatedAt", now),
                        UpdateOperators.set("reindexRequired", false));
    }

    /**
     * Get the datastore for the current security context realm.
     * Uses getSecurityContextRealmId() to derive realm from security context,
     * falling back to defaultRealm if no security context is available.
     */
    public dev.morphia.Datastore ds() {
        return ds(getSecurityContextRealmId());
    }

    /**
     * Get the datastore for a specific realm (used for explicit realm operations).
     */
    public dev.morphia.Datastore ds(String realm) {
        if (realm == null) return morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
        return morphiaDataStoreWrapper.getDataStore(realm);
    }
}
