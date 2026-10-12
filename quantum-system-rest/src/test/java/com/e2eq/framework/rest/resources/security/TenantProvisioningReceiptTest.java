package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.rest.responses.TenantProvisioningRunResponse;
import com.e2eq.framework.service.TenantProvisioningService.ProvisionTenantCommand;
import com.e2eq.framework.service.provisioning.TenantProvisioningRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantProvisioningReceiptTest {
    @Test
    void endpointPassesIdempotencyKeyAndReturnsAuthoritativePlacement() throws Exception {
        var endpoint = new TenantProvisioningResource();
        var producer = new RecordingRuns();
        endpoint.provisioningRunService = producer;
        var request = new TenantProvisioningResource.ProvisionTenantRequest();
        request.tenantEmailDomain = "acme.example";
        request.orgRefName = "acme";
        request.adminUserId = "operator";
        request.adminPassword = "secret-not-in-receipt";
        request.applicationId = "test-app";
        try (var response = endpoint.provision(request, "install-request-0001")) {
            assertEquals(201, response.getStatus());
            assertEquals("install-request-0001", producer.executionRef);
            var payload = new ObjectMapper().valueToTree(response.getEntity());
            assertEquals("acme-example", payload.path("tenantId").asText());
            assertEquals("shared-app", payload.path("realmId").asText());
            assertEquals("COMPLETED", payload.path("status").asText());
            assertFalse(payload.toString().contains(request.adminPassword));
            assertFalse(payload.has("adminPassword"));
        }
    }

    static class RecordingRuns extends TenantProvisioningRunService {
        String executionRef;
        @Override public TenantProvisioningRunResponse startProvisioning(ProvisionTenantCommand command, String ref) {
            executionRef = ref;
            return TenantProvisioningRunResponse.builder().executionRef(ref)
                .tenantId("acme-example").realmId("shared-app").deploymentTopology("POOLED_REALM")
                .status("COMPLETED").realmCreated(false).userCreated(true).build();
        }
    }
}
