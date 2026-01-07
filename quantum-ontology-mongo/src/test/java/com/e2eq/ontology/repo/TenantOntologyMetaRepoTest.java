package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.model.TenantOntologyMeta;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class TenantOntologyMetaRepoTest {

    @Inject
    TenantOntologyMetaRepo repo;

    private DataDomain tenant1;
    private DataDomain tenant2;

    @BeforeEach
    void setup() {
        // Clean up any existing data
        repo.deleteAll();
        
        tenant1 = new DataDomain("org1", "acc1", "tenant1", 0, "user1");
        tenant2 = new DataDomain("org1", "acc1", "tenant2", 0, "user2");
    }

    @Test
    void testUpsertObservation() {
        // Test creating new observation
        TenantOntologyMeta meta = repo.upsertObservation(tenant1, 1, "test.yaml", "1.0.0", false);
        
        assertNotNull(meta);
        assertEquals(Integer.valueOf(1), meta.getYamlVersion());
        assertEquals("test.yaml", meta.getSource());
        assertEquals("1.0.0", meta.getSoftwareVersion());
        assertFalse(meta.isReindexRequired());
        assertTrue(meta.isActive());
        assertNotNull(meta.getUpdatedAt());
    }

    @Test
    void testMarkApplied() {
        // Create initial observation
        repo.upsertObservation(tenant1, 1, "test.yaml", "1.0.0", true);
        
        // Mark as applied
        TenantOntologyMeta meta = repo.markApplied(tenant1, "yamlHash123", "tboxHash456", 1, "1.0.0", "test.yaml");
        
        assertNotNull(meta);
        assertEquals("yamlHash123", meta.getYamlHash());
        assertEquals("tboxHash456", meta.getTboxHash());
        assertEquals("test.yaml", meta.getSource());
        assertFalse(meta.isReindexRequired());
        assertNotNull(meta.getAppliedAt());
    }

    @Test
    void testTenantIsolation() {
        // Create metadata for two different tenants
        TenantOntologyMeta meta1 = repo.upsertObservation(tenant1, 1, "test1.yaml", "1.0.0", false);
        TenantOntologyMeta meta2 = repo.upsertObservation(tenant2, 2, "test2.yaml", "2.0.0", true);
        
        // Verify tenant1 metadata
        var retrieved1 = repo.getActiveMeta(tenant1);
        assertTrue(retrieved1.isPresent());
        assertEquals(Integer.valueOf(1), retrieved1.get().getYamlVersion());
        assertEquals("test1.yaml", retrieved1.get().getSource());
        
        // Verify tenant2 metadata
        var retrieved2 = repo.getActiveMeta(tenant2);
        assertTrue(retrieved2.isPresent());
        assertEquals(Integer.valueOf(2), retrieved2.get().getYamlVersion());
        assertEquals("test2.yaml", retrieved2.get().getSource());
        
        // Verify they are different instances
        assertNotEquals(retrieved1.get().getId(), retrieved2.get().getId());
    }

    @Test
    void testGetActiveMetaNotFound() {
        DataDomain nonExistentTenant = new DataDomain("org1", "acc1", "nonexistent", 0, "user1");
        var result = repo.getActiveMeta(nonExistentTenant);
        assertFalse(result.isPresent());
    }
}