package com.e2eq.framework.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.system.catalog.RealmCatalogService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantProvisioningServiceCatalogTest {

    @Test
    void initializeContextKeepsEmailDomainSeparateFromDataDomainIdentifiers() {
        TenantProvisioningService service = new TenantProvisioningService();
        service.realmCatalog = new RecordingRealmCatalog(null);

        TenantProvisioningService.ProvisionTenantCommand command =
            TenantProvisioningService.ProvisionTenantCommand.builder()
                .tenantDisplayName("BASF — Global Bulk Shipper")
                .tenantEmailDomain("basf.com")
                .orgRefName("basf.com")
                .accountId("0000000000")
                .adminUserId("admin@basf.com")
                .adminSubject("admin@basf.com")
                .adminPassword("unused-in-context-test")
                .build();

        TenantProvisioningService.ProvisioningContext context = service.initializeContext(command);

        assertEquals("basf-com", context.getRealmId());
        assertEquals("basf.com", context.getDesiredRealm().getEmailDomain());
        assertEquals("basf-com", context.getDomainContext().getTenantId());
        assertEquals("basf-com", context.getDataDomain().getTenantId());
        assertEquals("basf-com", context.getDomainContext().getOrgRefName());
        assertEquals("basf-com", context.getDataDomain().getOrgRefName());
        assertEquals("basf-com", context.getDesiredRealm().getDatabaseName());
    }

    @Test
    void rejectsHistoricalDomainSpellingThatCollidesWithExistingRealmId() {
        Realm existing = realm(
            "northstar-field-service-com",
            "northstar.field.service.com",
            "northstar.field.service.com"
        );
        Realm desired = realm(
            "northstar-field-service-com",
            "northstar-field-service.com",
            "northstar-field-service.com"
        );
        RecordingRealmCatalog catalog = new RecordingRealmCatalog(existing);
        TenantProvisioningService service = new TenantProvisioningService();
        service.realmCatalog = catalog;

        TenantProvisioningService.ProvisionTenantCommand command =
            TenantProvisioningService.ProvisionTenantCommand.builder()
                .tenantDisplayName("Northstar Field Service")
                .tenantEmailDomain("northstar-field-service.com")
                .orgRefName("northstar-field-service")
                .accountId("0000000001")
                .adminUserId("local-test")
                .adminSubject("local-test-subject")
                .adminPassword("unused-in-catalog-test")
                .build();
        TenantProvisioningService.ProvisioningContext context =
            TenantProvisioningService.ProvisioningContext.builder()
                .command(command)
                .result(new TenantProvisioningService.ProvisionResult())
                .systemRealm("system-com")
                .realmId("northstar-field-service-com")
                .desiredRealm(desired)
                .build();

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> service.ensureRealmCatalog(context)
        );

        assertTrue(error.getMessage().contains("emailDomain"));
        assertTrue(error.getMessage().contains("northstar.field.service.com"));
        assertTrue(error.getMessage().contains("northstar-field-service.com"));
        assertFalse(catalog.registerCalled);
    }

    private static Realm realm(String refName, String emailDomain, String tenantId) {
        DataDomain dataDomain = DataDomain.builder()
            .orgRefName("northstar-field-service")
            .accountNum("0000000001")
            .tenantId(tenantId)
            .ownerId("local-test")
            .build();
        DomainContext domainContext = DomainContext.builder()
            .tenantId(tenantId)
            .defaultRealm(refName)
            .orgRefName("northstar-field-service")
            .accountId("0000000001")
            .build();
        return Realm.builder()
            .refName(refName)
            .displayName("Northstar Field Service")
            .emailDomain(emailDomain)
            .databaseName(refName)
            .domainContext(domainContext)
            .dataDomain(dataDomain)
            .defaultAdminUserId("local-test")
            .defaultPerspective("TENANT_ADMIN")
            .build();
    }

    private static final class RecordingRealmCatalog extends RealmCatalogService {
        private final Realm existing;
        private boolean registerCalled;

        private RecordingRealmCatalog(Realm existing) {
            this.existing = existing;
        }

        @Override
        public Optional<Realm> findByEmailDomain(String emailDomain) {
            if (existing == null) {
                return Optional.empty();
            }
            return Optional.of(existing)
                .filter(realm -> realm.getEmailDomain().equals(emailDomain));
        }

        @Override
        public Optional<Realm> findByRefName(String refName) {
            if (existing == null) {
                return Optional.empty();
            }
            return Optional.of(existing)
                .filter(realm -> realm.getRefName().equals(refName));
        }

        @Override
        public String systemRealmId() {
            return "system-com";
        }

        @Override
        public Realm register(Realm realm) {
            registerCalled = true;
            return realm;
        }
    }
}
