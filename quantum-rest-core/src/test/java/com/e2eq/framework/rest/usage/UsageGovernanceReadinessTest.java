package com.e2eq.framework.rest.usage;

import com.e2eq.framework.annotations.FunctionalMapping;
import jakarta.ws.rs.GET;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsageGovernanceReadinessTest {

    @Test
    void rejectsAuthenticatedEndpointWithoutFunctionalMappingAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> UsageGovernanceReadiness.validateResourceClasses(
                        config("*"), Set.of(UnmappedResource.class)));
    }

    @Test
    void validatesFullConfiguredRuntimeIdentity() {
        assertDoesNotThrow(() -> UsageGovernanceReadiness.validateResourceClasses(
                config("sales:order:listOrders"), Set.of(MappedResource.class)));
        assertThrows(IllegalStateException.class,
                () -> UsageGovernanceReadiness.validateResourceClasses(
                        config("inventory:order:listOrders"), Set.of(MappedResource.class)));
    }

    private static UsageGovernanceConfig config(String selector) {
        return new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, "ENFORCE",
                UsageGovernanceConfig.OPENAPI_OPERATION_ID_STRATEGY, "METHOD",
                UsageGovernanceConfig.POLICY_ENDPOINTS, selector));
    }

    public static class UnmappedResource {
        @GET
        @Operation(operationId = "unmapped")
        public String get() {
            return "unmapped";
        }
    }

    @FunctionalMapping(area = "SALES", domain = "ORDER")
    public static class MappedResource {
        @GET
        @Operation(operationId = "listOrders")
        public String get() {
            return "mapped";
        }
    }
}
