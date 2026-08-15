package com.e2eq.framework.rest.usage;

import java.util.Locale;
import java.util.Objects;

/** Stable endpoint contract built from FunctionalMapping and OpenAPI operation identity. */
public record UsageEndpointIdentity(String functionalArea, String functionalDomain, String operationId) {

    public UsageEndpointIdentity {
        functionalArea = normalizeFunctionalPart(functionalArea, "functionalArea");
        functionalDomain = normalizeFunctionalPart(functionalDomain, "functionalDomain");
        operationId = requirePart(operationId, "operationId");
        if (operationId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("operationId must not contain ':'");
        }
    }

    public String canonicalName() {
        return functionalArea + ":" + functionalDomain + ":" + operationId;
    }

    public static UsageEndpointIdentity parse(String value) {
        Objects.requireNonNull(value, "endpoint identity");
        String[] parts = value.trim().split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "usage endpoint identity must be functionalArea:functionalDomain:operationId: " + value);
        }
        return new UsageEndpointIdentity(parts[0], parts[1], parts[2]);
    }

    private static String normalizeFunctionalPart(String value, String name) {
        return requirePart(value, name).toLowerCase(Locale.ROOT);
    }

    private static String requirePart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
