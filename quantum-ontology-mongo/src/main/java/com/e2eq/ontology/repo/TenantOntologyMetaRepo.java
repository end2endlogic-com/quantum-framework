package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.model.TenantOntologyMeta;
import com.mongodb.client.model.ReturnDocument;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Date;
import java.util.Optional;

/**
 * Repository for tenant-specific ontology metadata.
 */
@ApplicationScoped
public class TenantOntologyMetaRepo extends MorphiaRepo<TenantOntologyMeta> {

    /**
     * Delete all TenantOntologyMeta documents in the current realm.
     */
    public void deleteAll() {
        ds().getCollection(TenantOntologyMeta.class).deleteMany(new org.bson.Document());
    }

    /**
     * Get active ontology metadata for a tenant.
     */
    public Optional<TenantOntologyMeta> getActiveMeta(DataDomain dataDomain) {
        validateDataDomain(dataDomain);
        return Optional.ofNullable(ds().find(TenantOntologyMeta.class)
                .filter(dataDomainFilters(dataDomain))
                .filter(Filters.eq("active", true))
                .first());
    }

    /**
     * Record an observation of the current ontology YAML state for a tenant.
     */
    public TenantOntologyMeta upsertObservation(DataDomain dataDomain, Integer yamlVersion, 
                                               String source, String softwareVersion, boolean reindexRequired) {
        validateDataDomain(dataDomain);
        Date now = new Date();
        
        dev.morphia.ModifyOptions opts = new dev.morphia.ModifyOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER);
                
        return ds().find(TenantOntologyMeta.class)
                .filter(dataDomainFilters(dataDomain))
                .modify(opts, 
                        UpdateOperators.set("yamlVersion", yamlVersion),
                        UpdateOperators.set("source", source),
                        UpdateOperators.set("softwareVersion", softwareVersion),
                        UpdateOperators.set("updatedAt", now),
                        UpdateOperators.set("reindexRequired", reindexRequired),
                        UpdateOperators.set("active", true));
    }

    /**
     * Mark that the current YAML has been applied for a tenant.
     */
    public TenantOntologyMeta markApplied(DataDomain dataDomain, String yamlHash, 
                                         String tboxHash, Integer yamlVersion, 
                                         String softwareVersion, String source) {
        validateDataDomain(dataDomain);
        Date now = new Date();
        
        dev.morphia.ModifyOptions opts = new dev.morphia.ModifyOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER);
                
        return ds().find(TenantOntologyMeta.class)
                .filter(dataDomainFilters(dataDomain))
                .modify(opts,
                        UpdateOperators.set("yamlHash", yamlHash),
                        UpdateOperators.set("tboxHash", tboxHash),
                        UpdateOperators.set("yamlVersion", yamlVersion),
                        UpdateOperators.set("softwareVersion", softwareVersion),
                        UpdateOperators.set("source", source),
                        UpdateOperators.set("appliedAt", now),
                        UpdateOperators.set("updatedAt", now),
                        UpdateOperators.set("reindexRequired", false),
                        UpdateOperators.set("active", true));
    }

    private void validateDataDomain(DataDomain dd) {
        if (dd == null || dd.getTenantId() == null || dd.getTenantId().isBlank()) {
            throw new IllegalArgumentException("Valid DataDomain with tenantId must be provided");
        }
    }

    private dev.morphia.query.filters.Filter[] dataDomainFilters(DataDomain dd) {
        return new dev.morphia.query.filters.Filter[] {
            Filters.eq("dataDomain.orgRefName", dd.getOrgRefName()),
            Filters.eq("dataDomain.accountNum", dd.getAccountNum()),
            Filters.eq("dataDomain.tenantId", dd.getTenantId()),
            Filters.eq("dataDomain.dataSegment", dd.getDataSegment())
        };
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