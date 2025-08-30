package com.e2eq.framework.model.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Abstraction for different authentication providers used by the framework.
 */
public interface AuthProvider {
    SecurityIdentity validateAccessToken(String token);

    String getName();

    @RegisterForReflection
    record LoginNegativeResponse(
            @JsonProperty("userId") String userId,
            @JsonProperty("errorCode") int errorCode,
            @JsonProperty("errorReasonCode") int errorReasonCode,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("errorType") String errorType,
            @JsonProperty("errorDetails") String errorDetails
    ) {};
    @RegisterForReflection
    record LoginPositiveResponse(
            @JsonProperty("userId") String userId,
            @JsonProperty("identity") SecurityIdentity identity,
            @JsonProperty("roles") Set<String> roles,
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("refreshToken") String refreshToken,
            @JsonProperty("expirationTime") long expirationTime,
            @JsonProperty("mongodbUrl") String mongodbUrl,
            @JsonProperty("realm") String realm
            ) {};

    @RegisterForReflection
    record LoginResponse(
            @JsonProperty("authenticated") boolean authenticated,
            @JsonProperty("negativeResponse") LoginNegativeResponse negativeResponse,
            @JsonProperty("positiveResponse") LoginPositiveResponse positiveResponse
    ) {
        public LoginResponse(boolean authenticated, LoginNegativeResponse negativeResponse, LoginPositiveResponse positiveResponse) {
            this.authenticated = authenticated;
            this.negativeResponse = negativeResponse;
            this.positiveResponse = positiveResponse;
        }

        // Custom constructor for positive response
        public LoginResponse(boolean authenticated, LoginPositiveResponse positiveResponse) {
            this(authenticated, null, positiveResponse);
        }

        // Custom constructor for negative response
        public LoginResponse(boolean authenticated, LoginNegativeResponse negativeResponse) {
            this(authenticated, negativeResponse, null);
        }
    }

    LoginResponse login(String userId, String password);
    LoginResponse login(String realm, String userId, String password);
    LoginResponse refreshTokens(String refreshToken);
}
