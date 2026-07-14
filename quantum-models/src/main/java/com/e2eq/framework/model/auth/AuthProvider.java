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
            @JsonProperty("errorDetails") String errorDetails,
            @JsonProperty("realm") String realm
    ) {};
    @RegisterForReflection
    record LoginPositiveResponse(
            @JsonProperty("userId") String userId,
            @JsonProperty("identity") SecurityIdentity identity,
            @JsonProperty("roles") Set<String> roles,
            @JsonProperty("roleAssignments") java.util.List<RoleAssignment> roleAssignments,
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("refreshToken") String refreshToken,
            @JsonProperty("expirationTime") long expirationTime,
            @JsonProperty("mongodbUrl") String mongodbUrl,
            @JsonProperty("realm") String realm,
            // Application-scoped auth: the token's aud set (all apps authorized in the
            // active realm) and azp (the app actively entered). Null when app scoping
            // is not configured for this user/realm (legacy single-audience token).
            @JsonProperty("audiences") Set<String> audiences,
            @JsonProperty("activeApplication") String activeApplication
            ) {
        // Backward-compatible constructor (pre application-scoping): no aud set / azp.
        public LoginPositiveResponse(String userId,
                                     SecurityIdentity identity,
                                     Set<String> roles,
                                     java.util.List<RoleAssignment> roleAssignments,
                                     String accessToken,
                                     String refreshToken,
                                     long expirationTime,
                                     String mongodbUrl,
                                     String realm) {
            this(userId, identity, roles, roleAssignments, accessToken, refreshToken,
                 expirationTime, mongodbUrl, realm, null, null);
        }

        public LoginPositiveResponse(String userId,
                                     SecurityIdentity identity,
                                     Set<String> roles,
                                     String accessToken,
                                     String refreshToken,
                                     long expirationTime,
                                     String mongodbUrl,
                                     String realm) {
            this(userId,
                 identity,
                 roles,
                 // default roleAssignments derived from roles as CREDENTIAL source
                 roles == null ? java.util.List.of() : roles.stream()
                        .map(r -> new RoleAssignment(r, java.util.EnumSet.of(RoleSource.CREDENTIAL)))
                        .toList(),
                 accessToken,
                 refreshToken,
                 expirationTime,
                 mongodbUrl,
                 realm,
                 null,
                 null);
        }
    };

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

    /**
     * Application-scoped login (application-scoped auth). Providers that support it
     * resolve the user's authorized application set for the active realm, scope the
     * token's {@code aud}/{@code azp} accordingly, and may return a selection-required
     * or not-authorized negative response. The default ignores the applicationId and
     * behaves exactly like {@link #login(String, String)}, so providers that don't
     * model application scoping are unaffected.
     *
     * @param applicationId the application being signed into, or null to let the
     *                      provider resolve it (single → assumed, many → default/prompt)
     */
    default LoginResponse login(String userId, String password, String applicationId) {
        return login(userId, password);
    }

    LoginResponse refreshTokens(String refreshToken);

    /**
     * Returns the issuer URI this provider uses for tokens it issues/validates.
     * Used for multi-provider routing based on JWT iss claim.
     */
    default String getIssuer() { return null; }
}
