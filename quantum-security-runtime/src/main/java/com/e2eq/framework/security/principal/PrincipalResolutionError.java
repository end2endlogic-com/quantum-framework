package com.e2eq.framework.security.principal;

/**
 * Typed refusals of identity-only principal resolution
 * ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} sections 2 and 4). Each code carries the HTTP
 * status the resolution endpoint answers with.
 */
public enum PrincipalResolutionError {
    /** No credential for the verified identity. */
    PRINCIPAL_UNKNOWN(401),
    /** The credential is INACTIVE or DELETED. */
    PRINCIPAL_DISABLED(401),
    /** The identity is not granted the receiving application in the resolved realm. */
    APPLICATION_NOT_GRANTED(403),
    /** The requested realm is unregistered or outside the credential's realm authorization. */
    REALM_NOT_AUTHORIZED(403),
    /** The tenant is not authorized by the realm assignment, or not active in the realm. */
    TENANT_NOT_AUTHORIZED(403),
    /** Several tenants are reachable in a shared realm and the identity defines no default. */
    TENANT_SELECTION_REQUIRED(400),
    /** Impersonation is not configured, not authorized, or names an unknown identity. */
    IMPERSONATION_REFUSED(403),
    /** Acting on behalf of an identity without a delegating service credential, or outside its scope. */
    DELEGATION_NOT_ALLOWED(403),
    /** The acted-for identity has no credential. */
    DELEGATION_TARGET_UNKNOWN(403);

    private final int httpStatus;

    PrincipalResolutionError(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
