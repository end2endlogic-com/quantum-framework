package com.e2eq.framework.rest.usage;

/** Trusted tenant and subject identity contract. */
public record UsagePrincipalIdentity(String tenantId, String subjectId) {

    public UsagePrincipalIdentity {
        tenantId = require(tenantId, "tenantId");
        subjectId = require(subjectId, "subjectId");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
