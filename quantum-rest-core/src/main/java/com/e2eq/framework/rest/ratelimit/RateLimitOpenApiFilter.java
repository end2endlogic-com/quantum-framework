package com.e2eq.framework.rest.ratelimit;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;

/** Adds the global limiter's typed HTTP 429 contract to every REST operation. */
@OpenApiFilter(OpenApiFilter.RunStage.RUN)
public class RateLimitOpenApiFilter implements OASFilter {

    private static final String ERROR_SCHEMA_NAME = "QuantumRateLimitError";

    private final boolean active = ConfigProvider.getConfig()
            .getOptionalValue(RateLimitConfig.MODE, String.class)
            .map(RateLimitMode::parse)
            .map(mode -> mode != RateLimitMode.OFF)
            .orElse(false);

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (!active) {
            return;
        }
        Components components = openAPI.getComponents();
        if (components == null) {
            components = OASFactory.createComponents();
            openAPI.setComponents(components);
        }
        if (components.getSchemas() == null || !components.getSchemas().containsKey(ERROR_SCHEMA_NAME)) {
            components.addSchema(ERROR_SCHEMA_NAME, rateLimitErrorSchema());
        }
    }

    @Override
    public Operation filterOperation(Operation operation) {
        if (!active) {
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
        return operation;
    }

    private static APIResponse rateLimitedResponse() {
        Schema retryAfterSchema = OASFactory.createSchema()
                .addType(Schema.SchemaType.INTEGER)
                .format("int64")
                .minimum(java.math.BigDecimal.ONE);
        Header retryAfter = OASFactory.createHeader()
                .description("Whole seconds until a request may be retried")
                .required(true)
                .schema(retryAfterSchema);
        Schema errorSchema = OASFactory.createSchema().ref("#/components/schemas/" + ERROR_SCHEMA_NAME);
        MediaType errorMediaType = OASFactory.createMediaType().schema(errorSchema);
        Content content = OASFactory.createContent().addMediaType("application/json", errorMediaType);
        return OASFactory.createAPIResponse()
                .description("Request rate limit exceeded")
                .addHeader("Retry-After", retryAfter)
                .content(content);
    }

    private static Schema rateLimitErrorSchema() {
        Schema integer = OASFactory.createSchema().addType(Schema.SchemaType.INTEGER).format("int32");
        Schema text = OASFactory.createSchema().addType(Schema.SchemaType.STRING);
        return OASFactory.createSchema()
                .addType(Schema.SchemaType.OBJECT)
                .addProperty("status", integer)
                .addProperty("reasonCode", OASFactory.createSchema().addType(Schema.SchemaType.INTEGER).format("int32"))
                .addProperty("statusMessage", text)
                .addProperty("reasonMessage", OASFactory.createSchema().addType(Schema.SchemaType.STRING))
                .addRequired("status")
                .addRequired("reasonCode")
                .addRequired("statusMessage")
                .addRequired("reasonMessage");
    }
}
