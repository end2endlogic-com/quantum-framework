package com.e2eq.framework.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.api.tenant.TenantDeploymentTopology;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.system.catalog.RealmCatalogService;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.EnvConfigUtils;
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
        service.envConfigUtils = env();

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
        assertEquals(TenantDeploymentTopology.DEDICATED_REALM, context.getDeploymentTopology());
    }

    @Test
    void pooledContextSeparatesTenantVisibilityFromRealmPlacement() {
        Realm pool = realm("shared-app", "shared.example", "platform-owner");
        pool.setDeploymentType(RealmDeploymentType.SHARED);
        RecordingRealmCatalog catalog = new RecordingRealmCatalog(pool);
        RecordingMembershipService memberships = new RecordingMembershipService();
        TenantProvisioningService service = new TenantProvisioningService();
        service.realmCatalog = catalog;
        service.realmMembershipService = memberships;
        service.envConfigUtils = env();

        TenantProvisioningService.ProvisioningContext context = service.initializeContext(
            TenantProvisioningService.ProvisionTenantCommand.builder()
                .tenantDisplayName("Acme Corp")
                .tenantEmailDomain("acme.com")
                .orgRefName("acme.com")
                .accountId("4444555566")
                .adminUserId("admin@acme.com")
                .adminSubject("subject-acme")
                .adminPassword("unused-in-context-test")
                .deploymentTopology(TenantDeploymentTopology.POOLED_REALM)
                .placementRealmId("shared-app")
                .build()
        );

        assertEquals("shared-app", context.getRealmId());
        assertEquals("shared-app", context.getDomainContext().getDefaultRealm());
        assertEquals("acme-com", context.getTenantId());
        assertEquals("acme-com", context.getDomainContext().getTenantId());
        assertEquals("acme-com", context.getDataDomain().getTenantId());

        service.ensureRealmCatalog(context);
        service.runRealmMigrations(context);
        service.applyBaseSeedPacks(context);
        service.ensureRealmMembership(context);

        assertFalse(catalog.registerCalled);
        assertEquals(pool, context.getDesiredRealm());
        assertEquals(RealmTenantMembership.MEMBERSHIP_ROLE_PARTICIPANT,
            memberships.saved.getMembershipRole());
        assertEquals("shared-app", memberships.saved.getRealmRefName());
        assertEquals("acme-com", memberships.saved.getTenantId());
        assertEquals("admin@acme.com", memberships.saved.getDefaultAdminUserId());
    }

    @Test
    void pooledContextDerivesDefaultPodRealmFromApplication() {
        TenantProvisioningService service = new TenantProvisioningService();
        service.realmCatalog = new RecordingRealmCatalog(null);
        service.envConfigUtils = env();
        service.defaultPooledRealmPod = 1;

        TenantProvisioningService.ProvisioningContext context = service.initializeContext(
            TenantProvisioningService.ProvisionTenantCommand.builder()
                .tenantDisplayName("HelixorAI")
                .tenantEmailDomain("helixor.ai")
                .orgRefName("HelixorAI")
                .accountId("helixorai")
                .adminUserId("admin@helixor.ai")
                .adminSubject("subject-helixorai")
                .deploymentTopology(TenantDeploymentTopology.POOLED_REALM)
                .applicationId("Helixor Code")
                .build()
        );

        assertEquals("helixor-code-P1", context.getRealmId());
        assertEquals("helixor-code-P1", context.getDomainContext().getDefaultRealm());
        assertEquals("helixor-ai", context.getTenantId());
        assertEquals("helixorai", context.getDomainContext().getOrgRefName());
    }

    @Test
    void dedicatedContextDerivesApplicationScopedRealmName() {
        TenantProvisioningService service = new TenantProvisioningService();
        service.realmCatalog = new RecordingRealmCatalog(null);
        service.envConfigUtils = env();

        TenantProvisioningService.ProvisioningContext context = service.initializeContext(
            TenantProvisioningService.ProvisionTenantCommand.builder()
                .tenantDisplayName("End2End Logic")
                .tenantEmailDomain("end2endlogic.com")
                .orgRefName("end2endlogic")
                .accountId("end2endlogic")
                .adminUserId("admin@end2endlogic.com")
                .adminSubject("subject-end2endlogic")
                .deploymentTopology(TenantDeploymentTopology.DEDICATED_REALM)
                .applicationId("Helixor Code")
                .build()
        );

        assertEquals("helixor-code-D-end2endlogic-com", context.getRealmId());
        assertEquals("helixor-code-D-end2endlogic-com",
            context.getDomainContext().getDefaultRealm());
        assertEquals("end2endlogic-com", context.getTenantId());
        assertEquals("end2endlogic", context.getDomainContext().getOrgRefName());
    }

    @Test
    void pooledAdmissionRejectsRealmWithoutExplicitSharedType() {
        Realm legacyDedicated = realm(
            "legacy-realm", "legacy.example", "legacy-tenant");
        legacyDedicated.setDeploymentType(null);
        TenantProvisioningService service = new TenantProvisioningService();
        service.realmCatalog = new RecordingRealmCatalog(legacyDedicated);
        service.envConfigUtils = env();

        TenantProvisioningService.ProvisioningContext context =
            service.initializeContext(
                TenantProvisioningService.ProvisionTenantCommand.builder()
                    .tenantDisplayName("Acme Corp")
                    .tenantEmailDomain("acme.com")
                    .orgRefName("acme.com")
                    .accountId("4444555566")
                    .adminUserId("admin@acme.com")
                    .adminSubject("subject-acme")
                    .deploymentTopology(TenantDeploymentTopology.POOLED_REALM)
                    .placementRealmId("legacy-realm")
                    .build());

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> service.ensureRealmCatalog(context));

        assertTrue(error.getMessage().contains("explicitly marked SHARED"));
        assertEquals(RealmDeploymentType.DEDICATED,
            legacyDedicated.getDeploymentType());
    }

    @Test
    void pooledAdmissionRejectsUnprovenSeedArchetypes() {
        TenantProvisioningService service = new TenantProvisioningService();
        TenantProvisioningService.ProvisioningContext context =
            TenantProvisioningService.ProvisioningContext.builder()
                .deploymentTopology(TenantDeploymentTopology.POOLED_REALM)
                .realmId("shared-app")
                .tenantId("acme-com")
                .archetypes(java.util.List.of("starter"))
                .build();

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> service.applyRequestedArchetypes(context));

        assertTrue(error.getMessage().contains("tenant-scoped"));
    }

    @Test
    void pooledAdmissionFailsClosedWhenTargetRealmDoesNotExist() {
        TenantProvisioningService service = new TenantProvisioningService();
        service.realmCatalog = new RecordingRealmCatalog(null);
        service.envConfigUtils = env();

        TenantProvisioningService.ProvisioningContext context = service.initializeContext(
            TenantProvisioningService.ProvisionTenantCommand.builder()
                .tenantDisplayName("Acme Corp")
                .tenantEmailDomain("acme.com")
                .orgRefName("acme.com")
                .accountId("4444555566")
                .adminUserId("admin@acme.com")
                .adminSubject("subject-acme")
                .deploymentTopology(TenantDeploymentTopology.POOLED_REALM)
                .placementRealmId("missing-pool")
                .build()
        );

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> service.ensureRealmCatalog(context)
        );

        assertTrue(error.getMessage().contains("existing realm catalog entry"));
        assertFalse(service.realmCatalog.findByRefName("missing-pool").isPresent());
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
        service.envConfigUtils = env();

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

    private static EnvConfigUtils env() {
        EnvConfigUtils env = new EnvConfigUtils();
        env.setSystemRealm("system-com");
        return env;
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

    private static final class RecordingMembershipService extends RealmMembershipService {
        private RealmTenantMembership saved;

        @Override
        public RealmTenantMembership upsertMembership(RealmTenantMembership membership) {
            saved = membership;
            return membership;
        }
    }
}
