package com.e2eq.framework.security.principal;

/**
 * Typed refusals of identity-only principal resolution
 * ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} sections 2 and 4). Each code carries the HTTP
 * status the resolution endpoint answers with.
 */
public enum PrincipalResolutionError {
    /** No credential for the verified identity. */
    PRINCIPAL_UNKNOWN(401, "The verified identity is not known"),
    /** The credential is INACTIVE or DELETED. */
    PRINCIPAL_DISABLED(401, "The identity is disabled"),
    /** The identity is not granted the receiving application in the resolved realm. */
    APPLICATION_NOT_GRANTED(403, "The identity is not granted this application"),
    /** The requested realm is unregistered or outside the credential's realm authorization. */
    REALM_NOT_AUTHORIZED(403, "The identity is not authorized for the realm"),
    /** The tenant is not authorized by the realm assignment, or not active in the realm. */
    TENANT_NOT_AUTHORIZED(403, "The identity is not authorized for the tenant"),
    /** Several tenants are reachable in a shared realm and the identity defines no default. */
    TENANT_SELECTION_REQUIRED(400, "Select a tenant: the identity reaches several and defines no default"),
    /** Impersonation is not configured, not authorized, or names an unknown identity. */
    IMPERSONATION_REFUSED(403, "Impersonation refused"),
    /**
     * Acting on behalf of an identity without a delegating service credential, outside its realm
     * or scope, or for an identity that is unknown or disabled: one refusal, so delegation cannot
     * probe which identities exist.
     */
    DELEGATION_NOT_ALLOWED(403, "Delegation not allowed");

    private final int httpStatus;
    private final String publicMessage;

    PrincipalResolutionError(int httpStatus, String publicMessage) {
        this.httpStatus = httpStatus;
        this.publicMessage = publicMessage;
    }

    /**
     * The one message a caller sees for this code. It never names identities, realms, tenants
     * or stores, so a refusal does not tell apart, for example, an unknown impersonation target
     * from one the caller may not impersonate. The exception message carries the detail for logs.
     */
    public String publicMessage() {
        return publicMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
