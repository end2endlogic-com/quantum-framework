package com.e2eq.ontology.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry.*;
import com.e2eq.ontology.model.TenantOntologyMeta;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.TenantOntologyMetaRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.logging.Log;

import java.util.List;
import java.util.Map;

/**
 * Service to initialize tenant-specific ontology from YAML or rebuild from database.
 */
@ApplicationScoped
public class TenantOntologyService {

    @Inject
    TenantOntologyMetaRepo tenantMetaRepo;
    
    @Inject
    TenantOntologyTBoxRepo tenantTBoxRepo;

    /**
     * Initialize ontology for a tenant from TBox definition.
     */
    public void initializeTenantOntology(DataDomain dataDomain, TBox tbox, String source, String softwareVersion) {
        Log.infof("Initializing ontology for tenant: %s", dataDomain.getTenantId());
        
        String tboxHash = computeTBoxHash(tbox);
        String yamlHash = computeYamlHash(source);
        
        // Deactivate existing TBoxes
        tenantTBoxRepo.deactivateAll(dataDomain);
        
        // Create new TBox
        TenantOntologyTBox tenantTBox = new TenantOntologyTBox(tbox, tboxHash, yamlHash, source, softwareVersion);
        tenantTBox.setDataDomain(dataDomain);
        tenantTBox.setRefName("ontology-tbox-" + dataDomain.getTenantId());
        tenantTBox.setDisplayName("Ontology TBox for " + dataDomain.getTenantId());
        tenantTBoxRepo.save(tenantTBox);
        
        // Update metadata
        tenantMetaRepo.markApplied(dataDomain, yamlHash, tboxHash, 1, softwareVersion, source);
        
        Log.infof("Ontology initialized for tenant %s with hash %s", dataDomain.getTenantId(), tboxHash);
    }

    /**
     * Rebuild ontology from existing database relationships.
     */
    public void rebuildFromDatabase(DataDomain dataDomain, String softwareVersion) {
        Log.infof("Rebuilding ontology from database for tenant: %s", dataDomain.getTenantId());
        
        // This would analyze existing edges and infer the ontology structure
        // For now, create a minimal ontology that can be extended
        
        TBox minimalTBox = new TBox(Map.of(), Map.of(), List.of());
        initializeTenantOntology(dataDomain, minimalTBox, "database-rebuild", softwareVersion);
    }
    
    private String computeTBoxHash(TBox tbox) {
        // Simple hash computation - in real implementation use proper hashing
        return "tbox-" + System.currentTimeMillis();
    }
    
    private String computeYamlHash(String source) {
        // Simple hash computation - in real implementation use proper hashing
        return "yaml-" + source.hashCode();
    }
}