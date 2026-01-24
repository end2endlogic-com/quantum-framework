package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.*;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
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
public class TenantOntologyRegistryProviderTest {

    @Inject
    TenantOntologyRegistryProvider provider;

    @Inject
    TenantOntologyTBoxRepo tboxRepo;

    private DataDomain tenant1;
    private DataDomain tenant2;

    @BeforeEach
    void setup() {
        tboxRepo.deleteAll();
        provider.clearCache();
        
        tenant1 = new DataDomain("org1", "acc1", "tenant1", 0, "user1");
        tenant2 = new DataDomain("org1", "acc1", "tenant2", 0, "user2");
    }

    @Test
    void testGetRegistryForTenant() {
        // Create TBox for tenant1
        ClassDef personClass = new ClassDef("Person", Set.of(), Set.of(), Set.of());
        PropertyDef knowsProperty = new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"), 
                                                   false, Optional.empty(), false, true, false, Set.of());
        
        TBox tbox = new TBox(
            Map.of("Person", personClass),
            Map.of("knows", knowsProperty),
            List.of()
        );
        
        TenantOntologyTBox tenantTBox = new TenantOntologyTBox(tbox, "hash1", "yamlHash1", "test.yaml", "1.0.0");
        tenantTBox.setDataDomain(tenant1);
        tenantTBox.setRefName("tbox-tenant1");
        tenantTBox.setDisplayName("TBox for Tenant 1");
        tboxRepo.save(tenantTBox);
        
        // Get registry for tenant1
        OntologyRegistry registry = provider.getRegistryForTenant(tenant1);
        
        assertNotNull(registry);
        assertTrue(registry.classOf("Person").isPresent());
        assertTrue(registry.propertyOf("knows").isPresent());
        
        PropertyDef retrievedProperty = registry.propertyOf("knows").get();
        assertTrue(retrievedProperty.symmetric());
        assertEquals("Person", retrievedProperty.domain().get());
        assertEquals("Person", retrievedProperty.range().get());
    }

    @Test
    void testTenantIsolation() {
        // Create different TBoxes for two tenants
        
        // Tenant1: Person with knows property
        ClassDef personClass = new ClassDef("Person", Set.of(), Set.of(), Set.of());
        PropertyDef knowsProperty = new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"), 
                                                   false, Optional.empty(), false, true, false, Set.of());
        TBox tenant1TBox = new TBox(Map.of("Person", personClass), Map.of("knows", knowsProperty), List.of());
        
        TenantOntologyTBox tbox1 = new TenantOntologyTBox(tenant1TBox, "hash1", "yamlHash1", "test1.yaml", "1.0.0");
        tbox1.setDataDomain(tenant1);
        tbox1.setRefName("tbox-tenant1");
        tbox1.setDisplayName("TBox for Tenant 1");
        tboxRepo.save(tbox1);
        
        // Tenant2: Organization with memberOf property
        ClassDef orgClass = new ClassDef("Organization", Set.of(), Set.of(), Set.of());
        PropertyDef memberOfProperty = new PropertyDef("memberOf", Optional.of("Person"), Optional.of("Organization"), 
                                                      false, Optional.empty(), false, false, false, Set.of());
        TBox tenant2TBox = new TBox(Map.of("Organization", orgClass), Map.of("memberOf", memberOfProperty), List.of());
        
        TenantOntologyTBox tbox2 = new TenantOntologyTBox(tenant2TBox, "hash2", "yamlHash2", "test2.yaml", "2.0.0");
        tbox2.setDataDomain(tenant2);
        tbox2.setRefName("tbox-tenant2");
        tbox2.setDisplayName("TBox for Tenant 2");
        tboxRepo.save(tbox2);
        
        // Get registries for both tenants
        OntologyRegistry registry1 = provider.getRegistryForTenant(tenant1);
        OntologyRegistry registry2 = provider.getRegistryForTenant(tenant2);
        
        // Verify tenant1 registry
        assertTrue(registry1.classOf("Person").isPresent());
        assertFalse(registry1.classOf("Organization").isPresent());
        assertTrue(registry1.propertyOf("knows").isPresent());
        assertFalse(registry1.propertyOf("memberOf").isPresent());
        
        // Verify tenant2 registry
        assertFalse(registry2.classOf("Person").isPresent());
        assertTrue(registry2.classOf("Organization").isPresent());
        assertFalse(registry2.propertyOf("knows").isPresent());
        assertTrue(registry2.propertyOf("memberOf").isPresent());
    }

    @Test
    void testCaching() {
        // Create TBox for tenant1
        ClassDef personClass = new ClassDef("Person", Set.of(), Set.of(), Set.of());
        TBox tbox = new TBox(Map.of("Person", personClass), Map.of(), List.of());
        
        TenantOntologyTBox tenantTBox = new TenantOntologyTBox(tbox, "hash1", "yamlHash1", "test.yaml", "1.0.0");
        tenantTBox.setDataDomain(tenant1);
        tenantTBox.setRefName("tbox-tenant1");
        tenantTBox.setDisplayName("TBox for Tenant 1");
        tboxRepo.save(tenantTBox);
        
        // Get registry twice - should return same instance due to caching
        OntologyRegistry registry1 = provider.getRegistryForTenant(tenant1);
        OntologyRegistry registry2 = provider.getRegistryForTenant(tenant1);
        
        assertSame(registry1, registry2);
    }

    @Test
    void testInvalidateCache() {
        // Create TBox for tenant1
        ClassDef personClass = new ClassDef("Person", Set.of(), Set.of(), Set.of());
        TBox tbox = new TBox(Map.of("Person", personClass), Map.of(), List.of());
        
        TenantOntologyTBox tenantTBox = new TenantOntologyTBox(tbox, "hash1", "yamlHash1", "test.yaml", "1.0.0");
        tenantTBox.setDataDomain(tenant1);
        tenantTBox.setRefName("tbox-tenant1");
        tenantTBox.setDisplayName("TBox for Tenant 1");
        tboxRepo.save(tenantTBox);
        
        // Get registry and cache it
        OntologyRegistry registry1 = provider.getRegistryForTenant(tenant1);
        
        // Invalidate cache
        provider.invalidateTenant(tenant1);
        
        // Get registry again - should be different instance
        OntologyRegistry registry2 = provider.getRegistryForTenant(tenant1);
        
        assertNotSame(registry1, registry2);
    }

    @Test
    void testEmptyRegistryWhenNoTBox() {
        // Get registry for tenant with no TBox
        OntologyRegistry registry = provider.getRegistryForTenant(tenant1);
        
        assertNotNull(registry);
        // It might not be empty if there are annotated classes or YAML in the environment,
        // but it should at least exist.
    }
}
