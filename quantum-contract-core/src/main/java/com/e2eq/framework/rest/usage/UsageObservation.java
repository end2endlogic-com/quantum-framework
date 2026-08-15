package com.e2eq.framework.rest.usage;

import java.time.Duration;
import java.util.Objects;

/** Bounded observation contract emitted after a governed REST request. */
public record UsageObservation(
        String endpointId,
        String tenantId,
        String subjectId,
        String httpMethod,
        int responseStatus,
        Duration latency,
        long requestBytes,
        long responseBytes,
        UsageAdmissionDecision admission) {

    public UsageObservation {
        if (httpMethod == null || httpMethod.isBlank()) {
            throw new IllegalArgumentException("httpMethod must not be blank");
        }
        if (responseStatus < 100 || responseStatus > 599) {
            throw new IllegalArgumentException("responseStatus must be a valid HTTP status");
        }
        Objects.requireNonNull(latency, "latency");
        Objects.requireNonNull(admission, "admission");
        if (requestBytes < -1 || responseBytes < -1) {
            throw new IllegalArgumentException("byte counts must be -1 or greater");
        }
    }
}
