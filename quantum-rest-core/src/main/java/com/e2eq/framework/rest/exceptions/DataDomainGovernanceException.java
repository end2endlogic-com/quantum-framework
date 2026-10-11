package com.e2eq.framework.rest.exceptions;

/**
 * Raised when a REST write would place or move an entity outside the DataDomain the caller is
 * governed by. The REST layer never chooses a tenant placement from a request body: creates resolve
 * the domain from the authenticated principal (and its DataDomain policy), updates keep the stored
 * row's domain. Privileged cross-domain placement (seeding, tenant provisioning, migrations) is a
 * programmatic concern and does not go through these endpoints.
 */
public class DataDomainGovernanceException extends RuntimeException {

    public enum Code {
        /** The request body carried a DataDomain other than the one the caller is governed by. */
        DATA_DOMAIN_NOT_PERMITTED,
        /** A field-path update targeted dataDomain or one of its components. */
        DATA_DOMAIN_NOT_UPDATABLE,
        /** An id-carrying save named a row outside the caller's governed scope. */
        ENTITY_NOT_IN_SCOPE,
        /** The security context needed to govern the write is not established. */
        SECURITY_CONTEXT_MISSING
    }

    private final Code code;

    public DataDomainGovernanceException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
