package com.e2eq.framework.rest.usage;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** Validates active OpenAPI operation identity and publishes typed 429/503 responses in ENFORCE mode. */
@OpenApiFilter(OpenApiFilter.RunStage.RUN)
public class UsageGovernanceOpenApiFilter implements OASFilter {

    static final String ERROR_SCHEMA_NAME = "QuantumUsageGovernanceError";

    private final UsageGovernanceConfig config;

    public UsageGovernanceOpenApiFilter() {
        this(new UsageGovernanceConfig());
    }

    UsageGovernanceOpenApiFilter(UsageGovernanceConfig config) {
        this.config = config;
    }

    @Override
    public Operation filterOperation(Operation operation) {
        if (config.mode() != UsageGovernanceMode.ENFORCE) {
            return operation;
        }
        APIResponses responses = operation.getResponses();
        if (responses == null) {
            responses = OASFactory.createAPIResponses();
            operation.setResponses(responses);
        }
        if (!responses.hasAPIResponse("429")) {
            responses.addAPIResponse("429", rateLimitedResponse());
        }
        if (!responses.hasAPIResponse("503")) {
            responses.addAPIResponse("503", enforcementUnavailableResponse());
        }
        return operation;
    }

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (!config.active()) {
            return;
        }
        Set<String> operationIds = validateOperationIds(openAPI);
        validateConfiguredSelectors(operationIds);
        if (config.mode() == UsageGovernanceMode.ENFORCE) {
            Components components = openAPI.getComponents();
            if (components == null) {
                components = OASFactory.createComponents();
                openAPI.setComponents(components);
            }
            if (components.getSchemas() == null || !components.getSchemas().containsKey(ERROR_SCHEMA_NAME)) {
                components.addSchema(ERROR_SCHEMA_NAME, errorSchema());
            }
        }
    }

    private Set<String> validateOperationIds(OpenAPI openAPI) {
        Set<String> operationIds = new HashSet<>();
        if (openAPI.getPaths() == null || openAPI.getPaths().getPathItems() == null) {
            return operationIds;
        }
        for (PathItem path : openAPI.getPaths().getPathItems().values()) {
            if (path == null || path.getOperations() == null) {
                continue;
            }
            for (Operation operation : path.getOperations().values()) {
                if (operation == null) {
                    continue;
                }
                String operationId = operation.getOperationId();
                if (operationId == null || operationId.isBlank()) {
                    throw new IllegalStateException(
                            "Active usage governance requires every OpenAPI operation to have an operationId");
                }
                if (!operationIds.add(operationId)) {
                    throw new IllegalStateException(
                            "Active usage governance requires unique OpenAPI operationId values; duplicate: "
                                    + operationId);
                }
            }
        }
        return operationIds;
    }

    private void validateConfiguredSelectors(Set<String> operationIds) {
        for (String selector : config.policy().endpointSelectors()) {
            if (UsagePolicy.ALL_ENDPOINTS.equals(selector)) {
                continue;
            }
            String operationId = UsageEndpointIdentity.parse(selector).operationId();
            if (!operationIds.contains(operationId)) {
                throw new IllegalStateException(
                        UsageGovernanceConfig.POLICY_ENDPOINTS
                                + " references an operationId absent from OpenAPI: " + operationId);
            }
        }
    }

    private static APIResponse rateLimitedResponse() {
        Header retryAfter = OASFactory.createHeader()
                .description("Whole seconds until a request may be retried")
                .required(true)
                .schema(OASFactory.createSchema()
                        .addType(Schema.SchemaType.INTEGER)
                        .format("int64")
                        .minimum(BigDecimal.ONE));
        return errorResponse("Usage policy rejected the request")
                .addHeader(HttpHeaderNames.RETRY_AFTER, retryAfter);
    }

    private static APIResponse enforcementUnavailableResponse() {
        return errorResponse("Usage enforcement state is unavailable and admission failed closed");
    }

    private static APIResponse errorResponse(String description) {
        Schema error = OASFactory.createSchema().ref("#/components/schemas/" + ERROR_SCHEMA_NAME);
        MediaType mediaType = OASFactory.createMediaType().schema(error);
        Content content = OASFactory.createContent().addMediaType("application/json", mediaType);
        return OASFactory.createAPIResponse().description(description).content(content);
    }

    private static Schema errorSchema() {
        Schema integer = OASFactory.createSchema().addType(Schema.SchemaType.INTEGER).format("int32");
        Schema text = OASFactory.createSchema().addType(Schema.SchemaType.STRING);
        return OASFactory.createSchema()
                .addType(Schema.SchemaType.OBJECT)
                .addProperty("status", integer)
                .addProperty("code", text)
                .addProperty("message", OASFactory.createSchema().addType(Schema.SchemaType.STRING))
                .addProperty("endpointId", OASFactory.createSchema().addType(Schema.SchemaType.STRING))
                .addRequired("status")
                .addRequired("code")
                .addRequired("message");
    }

    private static final class HttpHeaderNames {
        private static final String RETRY_AFTER = "Retry-After";

        private HttpHeaderNames() {
        }
    }
}
