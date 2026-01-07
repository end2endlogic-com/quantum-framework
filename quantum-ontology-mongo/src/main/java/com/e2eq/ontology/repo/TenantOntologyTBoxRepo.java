package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.model.TenantOntologyTBox;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TenantOntologyTBoxRepo extends MorphiaRepo<TenantOntologyTBox> {

    /**
     * Delete all TenantOntologyTBox documents in the current realm.
     */
    public void deleteAll() {
        ds().getCollection(TenantOntologyTBox.class).deleteMany(new org.bson.Document());
    }

    /**
     * Get the active TBox for a tenant
     */
    public Optional<TenantOntologyTBox> findActiveTBox(DataDomain dataDomain) {
        validateDataDomain(dataDomain);
        Query<TenantOntologyTBox> q = ds().find(TenantOntologyTBox.class)
                .filter(dataDomainFilters(dataDomain))
                .filter(Filters.eq("active", true));
        
        FindOptions options = new FindOptions()
                .sort(Sort.descending("appliedAt"))
                .limit(1);
                
        List<TenantOntologyTBox> results = q.iterator(options).toList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find TBox by hash for a specific tenant
     */
    public Optional<TenantOntologyTBox> findByHash(DataDomain dataDomain, String tboxHash) {
        if (tboxHash == null || tboxHash.isBlank()) {
            return Optional.empty();
        }
        validateDataDomain(dataDomain);
        
        Query<TenantOntologyTBox> q = ds().find(TenantOntologyTBox.class)
                .filter(dataDomainFilters(dataDomain))
                .filter(Filters.eq("tboxHash", tboxHash));
        return Optional.ofNullable(q.first());
    }

    /**
     * Find TBox by software version for a tenant
     */
    public Optional<TenantOntologyTBox> findByVersion(DataDomain dataDomain, String softwareVersion) {
        if (softwareVersion == null || softwareVersion.isBlank()) {
            return Optional.empty();
        }
        validateDataDomain(dataDomain);
        
        Query<TenantOntologyTBox> q = ds().find(TenantOntologyTBox.class)
                .filter(dataDomainFilters(dataDomain))
                .filter(Filters.eq("softwareVersion", softwareVersion))
                .filter(Filters.eq("active", true));
                
        FindOptions options = new FindOptions()
                .sort(Sort.descending("appliedAt"))
                .limit(1);
                
        List<TenantOntologyTBox> results = q.iterator(options).toList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Deactivate all TBoxes for a tenant (before activating a new one)
     */
    public void deactivateAll(DataDomain dataDomain) {
        validateDataDomain(dataDomain);
        ds().find(TenantOntologyTBox.class)
                .filter(dataDomainFilters(dataDomain))
                .update(new dev.morphia.UpdateOptions().multi(true), 
                        dev.morphia.query.updates.UpdateOperators.set("active", false));
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