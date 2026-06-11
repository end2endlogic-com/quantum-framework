package com.e2eq.framework.service;

import com.e2eq.framework.api.tenant.TenantDeleteResult;
import com.e2eq.framework.api.tenant.TenantProvisionRequest;
import com.e2eq.framework.api.tenant.TenantProvisionResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Plain unit test: the TenantLifecycle contract DTOs and the embedded
 * service's command/result types must stay field-for-field equivalent —
 * the contract mirrors rather than replaces them (wp3 rule 2).
 */
class TenantLifecycleAdapterTest {

    @Test
    void requestMapsToCommandFieldForField() {
        TenantProvisionRequest request = TenantProvisionRequest.builder()
            .tenantDisplayName("Acme Corp")
            .tenantEmailDomain("acme.com")
            .orgRefName("acme.com")
            .accountId("4444555566")
            .adminUserId("admin@acme.com")
            .adminSubject("subj-123")
            .adminPassword("s3cret!")
            .archetypes(List.of("b2b-starter"))
            .overwriteAll(false)
            .build();

        TenantProvisioningService.ProvisionTenantCommand command =
            TenantProvisioningService.toCommand(request);

        Assertions.assertEquals("Acme Corp", command.getTenantDisplayName());
        Assertions.assertEquals("acme.com", command.getTenantEmailDomain());
        Assertions.assertEquals("acme.com", command.getOrgRefName());
        Assertions.assertEquals("4444555566", command.getAccountId());
        Assertions.assertEquals("admin@acme.com", command.getAdminUserId());
        Assertions.assertEquals("subj-123", command.getAdminSubject());
        Assertions.assertEquals("s3cret!", command.getAdminPassword());
        Assertions.assertEquals(List.of("b2b-starter"), command.getArchetypes());
        Assertions.assertFalse(command.isOverwriteAll());
    }

    @Test
    void requestValidatesRequiredFields() {
        Assertions.assertThrows(NullPointerException.class, () ->
            TenantProvisionRequest.builder().tenantEmailDomain("acme.com").build());
    }

    @Test
    void provisionResultMapsToContract() {
        TenantProvisioningService.ProvisionResult r = new TenantProvisioningService.ProvisionResult();
        r.realmId = "acme-com";
        r.realmCreated = true;
        r.userCreated = true;
        r.appliedSeedArchetypes.add("b2b-starter");
        r.addWarning("seed pack X skipped");

        TenantProvisionResult contract = TenantProvisioningService.toContractResult(r);

        Assertions.assertEquals("acme-com", contract.realmId());
        Assertions.assertTrue(contract.realmCreated());
        Assertions.assertTrue(contract.userCreated());
        Assertions.assertEquals(List.of("b2b-starter"), contract.appliedSeedArchetypes());
        Assertions.assertEquals(List.of("seed pack X skipped"), contract.warnings());
    }

    @Test
    void deleteResultMapsToContract() {
        TenantProvisioningService.DeleteResult r = new TenantProvisioningService.DeleteResult();
        r.realmId = "acme-com";
        r.realmCatalogDeleted = true;
        r.databaseDropped = true;
        r.deletedCredentialCount = 3;

        TenantDeleteResult contract = TenantProvisioningService.toContractResult(r);

        Assertions.assertEquals("acme-com", contract.realmId());
        Assertions.assertTrue(contract.realmCatalogDeleted());
        Assertions.assertTrue(contract.databaseDropped());
        Assertions.assertEquals(3, contract.deletedCredentialCount());
        Assertions.assertEquals(List.of(), contract.warnings());
    }
}
