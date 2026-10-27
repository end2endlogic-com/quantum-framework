package com.e2eq.framework.oauth.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** OAuth error fields; does not include client secrets or credential records. */
@Schema(additionalProperties = Schema.False.class)
public record OAuthErrorResponse(
        @Schema(required = true) String error,
        @JsonProperty("error_description") @Schema(required = true) String errorDescription) {
}
