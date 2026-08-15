package com.e2eq.framework.rest.usage;

import java.util.Objects;

/** Typed policy-source input contract. Unknown byte counts are represented by -1. */
public record UsageRequest(
        UsageEndpointIdentity endpoint,
        UsagePrincipalIdentity principal,
        String httpMethod,
        long requestBytes) {

    public UsageRequest {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(principal, "principal");
        if (httpMethod == null || httpMethod.isBlank()) {
            throw new IllegalArgumentException("httpMethod must not be blank");
        }
        httpMethod = httpMethod.trim().toUpperCase(java.util.Locale.ROOT);
        if (requestBytes < -1) {
            throw new IllegalArgumentException("requestBytes must be -1 or greater");
        }
    }
}
