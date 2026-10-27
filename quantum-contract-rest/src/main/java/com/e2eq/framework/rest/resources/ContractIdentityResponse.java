package com.e2eq.framework.rest.resources;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.util.Map;

/** Schema of the existing producer identity envelope; operation keys are dynamic. */
@Schema(additionalProperties = Schema.False.class)
public record ContractIdentityResponse(
        @JsonProperty("spec_sha256") @Schema(required = true) String specSha256,
        @Schema(required = true) String algorithm,
        @JsonProperty("contract_version") @Schema(required = true) String contractVersion,
        @Schema(required = true) String source,
        @JsonProperty("operation_algorithm") @Schema(required = true) String operationAlgorithm,
        @Schema(required = true) Map<String, OperationIdentity> operations) {
    @Schema(additionalProperties = Schema.False.class)
    public record OperationIdentity(@Schema(required = true) String sha256,
                                    @Schema(required = true) String method,
                                    @Schema(required = true) String path,
                                    String operationId) {}
}
