package com.e2eq.framework.rest.requests;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Request to generate a long-lived service token (e.g., for MCP servers, service accounts).
 * The subject is auto-generated as a UUID — callers should not control it.
 *
 * @param roles             roles to embed in the token (required)
 * @param expirationSeconds seconds until expiry; null for non-expiring (100-year token)
 * @param description       optional human-readable description for the credential
 * @param realm             optional realm to stamp as the token's signed {@code realm} claim.
 *                          Data planes running delegated-claims validation reject tokens
 *                          without one, so service tokens intended for tenant-plane calls
 *                          (e.g. the Install provisioning callback) must be realm-scoped.
 * @param audiences         optional application audiences ({@code aud}). Data planes that
 *                          enforce an expected audience reject the legacy default audience,
 *                          so service tokens intended for audience-enforcing applications
 *                          must name them here.
 */
@RegisterForReflection
public record ServiceTokenRequest(
        @NotNull Set<String> roles,
        Long expirationSeconds,
        String description,
        String realm,
        Set<String> audiences
) {}
