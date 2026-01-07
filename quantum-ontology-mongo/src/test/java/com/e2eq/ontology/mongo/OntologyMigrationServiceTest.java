package com.e2eq.ontology.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry.*;
import com.e2eq.ontology.model.OntologyMeta;
import com.e2eq.ontology.model.OntologyTBox;
import com.e2eq.ontology.repo.OntologyMetaRepo;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import com.e2eq.ontology.repo.TenantOntologyMetaRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class OntologyMigrationServiceTest {

    @Inject
    OntologyMigrationService migrationService;

    @Inject
    OntologyMetaRepo globalMetaRepo;

    @Inject
    OntologyTBoxRepo globalTBoxRepo;

    @Inject
    TenantOntologyMetaRepo tenantMetaRepo;

    @Inject
    TenantOntologyTBoxRepo tenantTBoxRepo;

    private DataDomain tenant1;
    private DataDomain tenant2;

    @BeforeEach
    void setup() {
        // Clean all repositories
        globalMetaRepo.deleteAll();
        globalTBoxRepo.deleteAll();
        tenantMetaRepo.deleteAll();
        tenantTBoxRepo.deleteAll();

        tenant1 = new DataDomain("org1", "acc1", "tenant1", 0, "user1");
        tenant2 = new DataDomain("org1", "acc1", "tenant2", 0, "user2");
    }

    @Test
    void testMigrateToTenantSpecific() {
        // Create global ontology metadata
        OntologyMeta globalMeta = new OntologyMeta();
        globalMeta.setRefName("global");
        globalMeta.setYamlHash("globalYamlHash");
        globalMeta.setTboxHash("globalTboxHash");
        globalMeta.setYamlVersion(1);
        globalMeta.setSource("global.yaml");
        globalMeta.setUpdatedAt(new Date());
        globalMeta.setAppliedAt(new Date());
        globalMeta.setReindexRequired(false);
        globalMetaRepo.save(globalMeta);

        // Create global TBox
        ClassDef personClass = new ClassDef("Person", Set.of(), Set.of(), Set.of());
        PropertyDef knowsProperty = new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"), 
                                                   false, Optional.empty(), false, true, false, Set.of());
        TBox tbox = new TBox(Map.of("Person", personClass), Map.of("knows", knowsProperty), List.of());

        OntologyTBox globalTBox = new OntologyTBox(tbox, "globalTboxHash", "globalYamlHash", "global.yaml");
        globalTBox.setRefName("global-tbox");
        globalTBox.setVersion(1);
        globalTBox.setDescription("Global ontology");
        globalTBoxRepo.save(globalTBox);

        // Perform migration
        List<DataDomain> tenants = List.of(tenant1, tenant2);
        migrationService.migrateToTenantSpecific(tenants, "1.0.0");

        // Verify tenant1 migration
        var tenant1Meta = tenantMetaRepo.getActiveMeta(tenant1);
        assertTrue(tenant1Meta.isPresent());
        assertEquals("globalYamlHash", tenant1Meta.get().getYamlHash());
        assertEquals("globalTboxHash", tenant1Meta.get().getTboxHash());
        assertEquals("1.0.0", tenant1Meta.get().getSoftwareVersion());
        assertTrue(tenant1Meta.get().isActive());

        var tenant1TBox = tenantTBoxRepo.findActiveTBox(tenant1);
        assertTrue(tenant1TBox.isPresent());
        assertEquals("globalTboxHash", tenant1TBox.get().getTboxHash());
        assertEquals("1.0.0", tenant1TBox.get().getSoftwareVersion());
        assertTrue(tenant1TBox.get().isActive());

        // Verify TBox content
        TBox reconstructed1 = tenant1TBox.get().toTBox();
        assertTrue(reconstructed1.classes().containsKey("Person"));
        assertTrue(reconstructed1.properties().containsKey("knows"));

        // Verify tenant2 migration
        var tenant2Meta = tenantMetaRepo.getActiveMeta(tenant2);
        assertTrue(tenant2Meta.isPresent());
        assertEquals("globalYamlHash", tenant2Meta.get().getYamlHash());
        assertEquals("1.0.0", tenant2Meta.get().getSoftwareVersion());

        var tenant2TBox = tenantTBoxRepo.findActiveTBox(tenant2);
        assertTrue(tenant2TBox.isPresent());
        assertEquals("globalTboxHash", tenant2TBox.get().getTboxHash());

        // Verify TBox content for tenant2
        TBox reconstructed2 = tenant2TBox.get().toTBox();
        assertTrue(reconstructed2.classes().containsKey("Person"));
        assertTrue(reconstructed2.properties().containsKey("knows"));

        // Verify tenants have separate instances
        assertNotEquals(tenant1Meta.get().getId(), tenant2Meta.get().getId());
        assertNotEquals(tenant1TBox.get().getId(), tenant2TBox.get().getId());
    }

    @Test
    void testMigrationWithNoGlobalOntology() {
        // Attempt migration with no global ontology
        List<DataDomain> tenants = List.of(tenant1);
        
        // Should not throw exception, just log warning
        assertDoesNotThrow(() -> {
            migrationService.migrateToTenantSpecific(tenants, "1.0.0");
        });

        // Verify no tenant ontology was created
        var tenantMeta = tenantMetaRepo.getActiveMeta(tenant1);
        assertFalse(tenantMeta.isPresent());

        var tenantTBox = tenantTBoxRepo.findActiveTBox(tenant1);
        assertFalse(tenantTBox.isPresent());
    }
}