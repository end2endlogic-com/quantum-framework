package com.e2eq.framework.rest.usage;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Versioned, declarative token-bucket policy contract returned by a UsagePolicySource. */
public record UsagePolicy(
        String id,
        String version,
        long requestLimit,
        Duration refillPeriod,
        Set<String> endpointSelectors) {

    public static final String ALL_ENDPOINTS = "*";

    public UsagePolicy {
        id = require(id, "id");
        version = require(version, "version");
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit must be greater than zero");
        }
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refillPeriod must be greater than zero");
        }
        try {
            refillPeriod.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("refillPeriod is too large", exception);
        }
        if (endpointSelectors == null || endpointSelectors.isEmpty()) {
            throw new IllegalArgumentException("endpointSelectors must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String selector : endpointSelectors) {
            String value = require(selector, "endpointSelector");
            normalized.add(ALL_ENDPOINTS.equals(value)
                    ? ALL_ENDPOINTS
                    : UsageEndpointIdentity.parse(value).canonicalName());
        }
        endpointSelectors = Set.copyOf(normalized);
    }

    public boolean appliesTo(UsageEndpointIdentity endpoint) {
        return endpointSelectors.contains(ALL_ENDPOINTS)
                || endpointSelectors.contains(endpoint.canonicalName());
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
