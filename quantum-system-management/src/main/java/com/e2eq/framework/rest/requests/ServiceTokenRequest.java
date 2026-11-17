package com.e2eq.framework.rest.requests;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Request to generate a long-lived service token (e.g., for MCP servers, service accounts).
 * The subject is auto-generated as a UUID — callers should not control it.
 *
 * @param roles             roles to embed in the token (required)
 * @param expirationSeconds seconds until expiry. Null yields a 100-year token and is
 *                          accepted only for an identity-agnostic service token. A token
 *                          bound to the caller requires a positive finite duration.
 * @param description       optional human-readable description for the credential
 * @param realm             retained for wire compatibility; ignored for an identity-agnostic
 *                          token. A token bound to the caller uses the caller's own realm.
 * @param audiences         retained for wire compatibility; ignored. A service
 *                          token identifies its caller and is not resource-bound.
 * @param authorizedTenantIds tenants the service may select within {@code realm}
 * @param userId            when set, must be the authenticated caller's own user id.
 *                          The issued token is that user's token for {@code applicationRef}.
 *                          Omit both to mint an identity-agnostic service token, which
 *                          requires a separate permission.
 * @param applicationRef    the one service a caller-bound token is for. Launchpad
 *                          resolves that service's identity and billing account from it.
 *                          An identity-agnostic token does not name one service.
 */
@RegisterForReflection
public record ServiceTokenRequest(
        @NotNull Set<String> roles,
        Long expirationSeconds,
        String description,
        String realm,
        Set<String> audiences,
        Set<String> authorizedTenantIds,
        String userId,
        String applicationRef
) {
    /** Wire-compatible constructor for callers that do not bind a user or a service. */
    public ServiceTokenRequest(
            Set<String> roles,
            Long expirationSeconds,
            String description,
            String realm,
            Set<String> audiences,
            Set<String> authorizedTenantIds) {
        this(roles, expirationSeconds, description, realm, audiences, authorizedTenantIds, null, null);
    }

    /** Binds a user and leaves the service unset. */
    public ServiceTokenRequest(
            Set<String> roles,
            Long expirationSeconds,
            String description,
            String realm,
            Set<String> audiences,
            Set<String> authorizedTenantIds,
            String userId) {
        this(roles, expirationSeconds, description, realm, audiences, authorizedTenantIds, userId, null);
    }
}
