package com.e2eq.framework.rest.usage;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageGovernanceOpenApiFilterTest {

    @Test
    void enforcePublishesTyped429And503AndValidatesConfiguredOperation() {
        UsageGovernanceConfig config = config("sales:order:createOrder");
        UsageGovernanceOpenApiFilter filter = new UsageGovernanceOpenApiFilter(config);
        Operation operation = OASFactory.createOperation().operationId("createOrder");
        operation.setResponses(OASFactory.createAPIResponses());
        filter.filterOperation(operation);
        OpenAPI openAPI = openApi("/orders", PathItem.HttpMethod.POST, operation);

        filter.filterOpenAPI(openAPI);

        var rateLimited = operation.getResponses().getAPIResponse("429");
        assertNotNull(rateLimited);
        assertNotNull(rateLimited.getHeaders().get("Retry-After"));
        assertTrue(rateLimited.getHeaders().get("Retry-After").getRequired());
        assertNotNull(operation.getResponses().getAPIResponse("503"));
        assertNotNull(openAPI.getComponents().getSchemas()
                .get(UsageGovernanceOpenApiFilter.ERROR_SCHEMA_NAME));
    }

    @Test
    void activeModeRejectsMissingOrUnknownOperationIdentity() {
        UsageGovernanceOpenApiFilter wildcard = new UsageGovernanceOpenApiFilter(config("*"));
        Operation missing = OASFactory.createOperation();
        assertThrows(IllegalStateException.class,
                () -> wildcard.filterOpenAPI(openApi("/missing", PathItem.HttpMethod.GET, missing)));

        UsageGovernanceOpenApiFilter exact = new UsageGovernanceOpenApiFilter(
                config("sales:order:configuredButAbsent"));
        Operation present = OASFactory.createOperation().operationId("differentOperation");
        assertThrows(IllegalStateException.class,
                () -> exact.filterOpenAPI(openApi("/orders", PathItem.HttpMethod.GET, present)));
    }

    private static UsageGovernanceConfig config(String selectors) {
        return new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, "ENFORCE",
                UsageGovernanceConfig.OPENAPI_OPERATION_ID_STRATEGY, "METHOD",
                UsageGovernanceConfig.POLICY_ENDPOINTS, selectors));
    }

    private static OpenAPI openApi(String pathName, PathItem.HttpMethod method, Operation operation) {
        PathItem pathItem = OASFactory.createPathItem();
        pathItem.setOperation(method, operation);
        Paths paths = OASFactory.createPaths();
        paths.addPathItem(pathName, pathItem);
        OpenAPI openAPI = OASFactory.createOpenAPI();
        openAPI.setPaths(paths);
        return openAPI;
    }
}
