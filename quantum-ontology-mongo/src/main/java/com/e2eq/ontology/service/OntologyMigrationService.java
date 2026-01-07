package com.e2eq.ontology.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.model.OntologyMeta;
import com.e2eq.ontology.model.OntologyTBox;
import com.e2eq.ontology.model.TenantOntologyMeta;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyMetaRepo;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import com.e2eq.ontology.repo.TenantOntologyMetaRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.logging.Log;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * Service to migrate from global ontology to tenant-specific ontology.
 */
@ApplicationScoped
public class OntologyMigrationService {

    @Inject
    OntologyMetaRepo globalMetaRepo;
    
    @Inject
    OntologyTBoxRepo globalTBoxRepo;
    
    @Inject
    TenantOntologyMetaRepo tenantMetaRepo;
    
    @Inject
    TenantOntologyTBoxRepo tenantTBoxRepo;

    /**
     * Migrate global ontology to tenant-specific for a list of tenants.
     */
    public void migrateToTenantSpecific(List<DataDomain> tenantDataDomains, String defaultSoftwareVersion) {
        Log.info("Starting migration from global to tenant-specific ontology");
        
        // Get the current global ontology
        var globalMeta = globalMetaRepo.getSingleton();
        var globalTBox = globalTBoxRepo.findLatest();
        
        if (globalMeta.isEmpty() || globalTBox.isEmpty()) {
            Log.warn("No global ontology found to migrate");
            return;
        }
        
        OntologyMeta meta = globalMeta.get();
        OntologyTBox tbox = globalTBox.get();
        
        // Migrate to each tenant
        for (DataDomain dataDomain : tenantDataDomains) {
            try {
                migrateTenantOntology(dataDomain, meta, tbox, defaultSoftwareVersion);
                Log.infof("Successfully migrated ontology for tenant: %s", dataDomain.getTenantId());
            } catch (Exception e) {
                Log.errorf(e, "Failed to migrate ontology for tenant: %s", dataDomain.getTenantId());
            }
        }
        
        Log.info("Completed ontology migration");
    }

    private void migrateTenantOntology(DataDomain dataDomain, OntologyMeta globalMeta, 
                                     OntologyTBox globalTBox, String softwareVersion) {
        
        // Create tenant-specific metadata
        TenantOntologyMeta tenantMeta = new TenantOntologyMeta();
        tenantMeta.setDataDomain(dataDomain);
        tenantMeta.setRefName("ontology-meta-" + dataDomain.getTenantId());
        tenantMeta.setDisplayName("Ontology Metadata for " + dataDomain.getTenantId());
        tenantMeta.setYamlHash(globalMeta.getYamlHash());
        tenantMeta.setTboxHash(globalMeta.getTboxHash());
        tenantMeta.setYamlVersion(globalMeta.getYamlVersion());
        tenantMeta.setSource(globalMeta.getSource());
        tenantMeta.setUpdatedAt(globalMeta.getUpdatedAt());
        tenantMeta.setAppliedAt(globalMeta.getAppliedAt());
        tenantMeta.setReindexRequired(globalMeta.isReindexRequired());
        tenantMeta.setActive(true);
        tenantMeta.setSoftwareVersion(softwareVersion);
        
        tenantMetaRepo.save(tenantMeta);
        
        // Create tenant-specific TBox
        TenantOntologyTBox tenantTBox = new TenantOntologyTBox();
        tenantTBox.setDataDomain(dataDomain);
        tenantTBox.setRefName("ontology-tbox-" + dataDomain.getTenantId());
        tenantTBox.setDisplayName("Ontology TBox for " + dataDomain.getTenantId());
        tenantTBox.setTboxHash(globalTBox.getTboxHash());
        tenantTBox.setYamlHash(globalTBox.getYamlHash());
        tenantTBox.setAppliedAt(globalTBox.getAppliedAt());
        tenantTBox.setSource(globalTBox.getSource());
        tenantTBox.setActive(true);
        tenantTBox.setSoftwareVersion(softwareVersion);
        // Convert data structures from global to tenant format
        Map<String, TenantOntologyTBox.ClassDefData> tenantClasses = new HashMap<>();
        if (globalTBox.getClasses() != null) {
            for (var entry : globalTBox.getClasses().entrySet()) {
                var globalClass = entry.getValue();
                var tenantClass = new TenantOntologyTBox.ClassDefData();
                tenantClass.setName(globalClass.getName());
                tenantClass.setParents(globalClass.getParents());
                tenantClass.setDisjointWith(globalClass.getDisjointWith());
                tenantClass.setSameAs(globalClass.getSameAs());
                tenantClasses.put(entry.getKey(), tenantClass);
            }
        }
        
        Map<String, TenantOntologyTBox.PropertyDefData> tenantProperties = new HashMap<>();
        if (globalTBox.getProperties() != null) {
            for (var entry : globalTBox.getProperties().entrySet()) {
                var globalProp = entry.getValue();
                var tenantProp = new TenantOntologyTBox.PropertyDefData();
                tenantProp.setName(globalProp.getName());
                tenantProp.setDomain(globalProp.getDomain());
                tenantProp.setRange(globalProp.getRange());
                tenantProp.setInverse(globalProp.isInverse());
                tenantProp.setInverseOf(globalProp.getInverseOf());
                tenantProp.setTransitive(globalProp.isTransitive());
                tenantProp.setSymmetric(globalProp.isSymmetric());
                tenantProp.setFunctional(globalProp.isFunctional());
                tenantProp.setSubPropertyOf(globalProp.getSubPropertyOf());
                tenantProperties.put(entry.getKey(), tenantProp);
            }
        }
        
        List<TenantOntologyTBox.PropertyChainDefData> tenantChains = new ArrayList<>();
        if (globalTBox.getChains() != null) {
            for (var globalChain : globalTBox.getChains()) {
                var tenantChain = new TenantOntologyTBox.PropertyChainDefData();
                tenantChain.setChain(globalChain.getChain());
                tenantChain.setImplies(globalChain.getImplies());
                tenantChains.add(tenantChain);
            }
        }
        
        tenantTBox.setClasses(tenantClasses);
        tenantTBox.setProperties(tenantProperties);
        tenantTBox.setChains(tenantChains);
        tenantTBox.setVersion(globalTBox.getVersion());
        tenantTBox.setDescription(globalTBox.getDescription());
        
        tenantTBoxRepo.save(tenantTBox);
    }
}