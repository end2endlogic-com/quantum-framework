package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry.*;
import com.e2eq.ontology.repo.TenantOntologyMetaRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.service.TenantOntologyService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class TenantOntologyServiceTest {

    @Inject
    TenantOntologyService service;

    @Inject
    TenantOntologyMetaRepo metaRepo;

    @Inject
    TenantOntologyTBoxRepo tboxRepo;

    private DataDomain tenant1;

    @BeforeEach
    void setup() {
        metaRepo.deleteAll();
        tboxRepo.deleteAll();
        
        tenant1 = new DataDomain("org1", "acc1", "tenant1", 0, "user1");
    }

    @Test
    void testInitializeTenantOntology() {
        // Create sample TBox
        ClassDef personClass = new ClassDef("Person", Set.of(), Set.of(), Set.of());
        PropertyDef knowsProperty = new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"), 
                                                   false, Optional.empty(), false, true, false, Set.of());
        
        TBox tbox = new TBox(
            Map.of("Person", personClass),
            Map.of("knows", knowsProperty),
            List.of()
        );
        
        // Initialize ontology
        service.initializeTenantOntology(tenant1, tbox, "test.yaml", "1.0.0");
        
        // Verify TBox was created
        var savedTBox = tboxRepo.findActiveTBox(tenant1);
        assertTrue(savedTBox.isPresent());
        assertEquals("1.0.0", savedTBox.get().getSoftwareVersion());
        assertTrue(savedTBox.get().isActive());
        
        // Verify metadata was created
        var savedMeta = metaRepo.getActiveMeta(tenant1);
        assertTrue(savedMeta.isPresent());
        assertEquals("1.0.0", savedMeta.get().getSoftwareVersion());
        assertTrue(savedMeta.get().isActive());
        
        // Verify TBox content
        TBox reconstructed = savedTBox.get().toTBox();
        assertTrue(reconstructed.classes().containsKey("Person"));
        assertTrue(reconstructed.properties().containsKey("knows"));
    }

    @Test
    void testRebuildFromDatabase() {
        // Test rebuild functionality
        service.rebuildFromDatabase(tenant1, "1.0.0");
        
        // Verify minimal ontology was created
        var savedTBox = tboxRepo.findActiveTBox(tenant1);
        assertTrue(savedTBox.isPresent());
        assertEquals("database-rebuild", savedTBox.get().getSource());
        
        var savedMeta = metaRepo.getActiveMeta(tenant1);
        assertTrue(savedMeta.isPresent());
        assertEquals("database-rebuild", savedMeta.get().getSource());
    }

    @Test
    void testDeactivatesPreviousTBox() {
        // Create first TBox
        TBox tbox1 = new TBox(Map.of(), Map.of(), List.of());
        service.initializeTenantOntology(tenant1, tbox1, "test1.yaml", "1.0.0");
        
        // Create second TBox
        TBox tbox2 = new TBox(Map.of(), Map.of(), List.of());
        service.initializeTenantOntology(tenant1, tbox2, "test2.yaml", "1.1.0");
        
        // Verify only the latest is active
        var activeTBox = tboxRepo.findActiveTBox(tenant1);
        assertTrue(activeTBox.isPresent());
        assertEquals("test2.yaml", activeTBox.get().getSource());
        assertEquals("1.1.0", activeTBox.get().getSoftwareVersion());
    }
}