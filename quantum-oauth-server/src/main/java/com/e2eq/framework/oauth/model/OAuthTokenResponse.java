package com.e2eq.framework.oauth.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Existing OAuth wire contract, shared by all supported grant types. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(additionalProperties = Schema.False.class)
public record OAuthTokenResponse(
        @JsonProperty("access_token") @Schema(required = true) String accessToken,
        @JsonProperty("token_type") @Schema(required = true) String tokenType,
        @JsonProperty("expires_in") @Schema(required = true) long expiresIn,
        @JsonProperty("refresh_token") String refreshToken) {
    @Override public String toString() {
        return "OAuthTokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", tokens=REDACTED]";
    }
}
