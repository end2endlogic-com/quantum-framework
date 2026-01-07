package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry.*;
import com.e2eq.ontology.model.TenantOntologyTBox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class TenantOntologyTBoxRepoTest {

    @Inject
    TenantOntologyTBoxRepo repo;

    private DataDomain tenant1;
    private DataDomain tenant2;
    private TBox sampleTBox;

    @BeforeEach
    void setup() {
        repo.deleteAll();
        
        tenant1 = new DataDomain("org1", "acc1", "tenant1", 0, "user1");
        tenant2 = new DataDomain("org1", "acc1", "tenant2", 0, "user2");
        
        // Create sample TBox
        ClassDef personClass = new ClassDef("Person", Set.of(), Set.of(), Set.of());
        PropertyDef knowsProperty = new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"), 
                                                   false, Optional.empty(), false, true, false, Set.of());
        
        sampleTBox = new TBox(
            Map.of("Person", personClass),
            Map.of("knows", knowsProperty),
            List.of()
        );
    }

    @Test
    void testSaveAndFindActiveTBox() {
        // Create and save TBox for tenant1
        TenantOntologyTBox tbox = new TenantOntologyTBox(sampleTBox, "hash123", "yamlHash123", "test.yaml", "1.0.0");
        tbox.setDataDomain(tenant1);
        tbox.setRefName("tbox-tenant1");
        tbox.setDisplayName("TBox for Tenant 1");
        
        repo.save(tbox);
        
        // Retrieve active TBox
        var retrieved = repo.findActiveTBox(tenant1);
        assertTrue(retrieved.isPresent());
        assertEquals("hash123", retrieved.get().getTboxHash());
        assertEquals("1.0.0", retrieved.get().getSoftwareVersion());
        assertTrue(retrieved.get().isActive());
        
        // Verify TBox content
        TBox reconstructed = retrieved.get().toTBox();
        assertTrue(reconstructed.classes().containsKey("Person"));
        assertTrue(reconstructed.properties().containsKey("knows"));
    }

    @Test
    void testTenantIsolation() {
        // Create TBox for tenant1
        TenantOntologyTBox tbox1 = new TenantOntologyTBox(sampleTBox, "hash1", "yamlHash1", "test1.yaml", "1.0.0");
        tbox1.setDataDomain(tenant1);
        tbox1.setRefName("tbox-tenant1");
        tbox1.setDisplayName("TBox for Tenant 1");
        repo.save(tbox1);
        
        // Create different TBox for tenant2
        ClassDef orgClass = new ClassDef("Organization", Set.of(), Set.of(), Set.of());
        TBox tenant2TBox = new TBox(Map.of("Organization", orgClass), Map.of(), List.of());
        
        TenantOntologyTBox tbox2 = new TenantOntologyTBox(tenant2TBox, "hash2", "yamlHash2", "test2.yaml", "2.0.0");
        tbox2.setDataDomain(tenant2);
        tbox2.setRefName("tbox-tenant2");
        tbox2.setDisplayName("TBox for Tenant 2");
        repo.save(tbox2);
        
        // Verify tenant1 TBox
        var retrieved1 = repo.findActiveTBox(tenant1);
        assertTrue(retrieved1.isPresent());
        assertTrue(retrieved1.get().toTBox().classes().containsKey("Person"));
        assertFalse(retrieved1.get().toTBox().classes().containsKey("Organization"));
        
        // Verify tenant2 TBox
        var retrieved2 = repo.findActiveTBox(tenant2);
        assertTrue(retrieved2.isPresent());
        assertTrue(retrieved2.get().toTBox().classes().containsKey("Organization"));
        assertFalse(retrieved2.get().toTBox().classes().containsKey("Person"));
    }

    @Test
    void testFindByHash() {
        TenantOntologyTBox tbox = new TenantOntologyTBox(sampleTBox, "uniqueHash", "yamlHash", "test.yaml", "1.0.0");
        tbox.setDataDomain(tenant1);
        tbox.setRefName("tbox-test");
        tbox.setDisplayName("Test TBox");
        repo.save(tbox);
        
        var found = repo.findByHash(tenant1, "uniqueHash");
        assertTrue(found.isPresent());
        assertEquals("uniqueHash", found.get().getTboxHash());
        
        // Should not find for different tenant
        var notFound = repo.findByHash(tenant2, "uniqueHash");
        assertFalse(notFound.isPresent());
    }

    @Test
    void testFindByVersion() {
        TenantOntologyTBox tbox = new TenantOntologyTBox(sampleTBox, "hash", "yamlHash", "test.yaml", "1.5.0");
        tbox.setDataDomain(tenant1);
        tbox.setRefName("tbox-version");
        tbox.setDisplayName("Version Test TBox");
        repo.save(tbox);
        
        var found = repo.findByVersion(tenant1, "1.5.0");
        assertTrue(found.isPresent());
        assertEquals("1.5.0", found.get().getSoftwareVersion());
    }

    @Test
    void testDeactivateAll() {
        // Create multiple TBoxes for tenant1
        TenantOntologyTBox tbox1 = new TenantOntologyTBox(sampleTBox, "hash1", "yamlHash1", "test1.yaml", "1.0.0");
        tbox1.setDataDomain(tenant1);
        tbox1.setRefName("tbox1");
        tbox1.setDisplayName("TBox 1");
        repo.save(tbox1);
        
        TenantOntologyTBox tbox2 = new TenantOntologyTBox(sampleTBox, "hash2", "yamlHash2", "test2.yaml", "1.1.0");
        tbox2.setDataDomain(tenant1);
        tbox2.setRefName("tbox2");
        tbox2.setDisplayName("TBox 2");
        repo.save(tbox2);
        
        // Deactivate all for tenant1
        repo.deactivateAll(tenant1);
        
        // Verify no active TBox for tenant1
        var active = repo.findActiveTBox(tenant1);
        assertFalse(active.isPresent());
    }
}