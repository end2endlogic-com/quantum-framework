package com.e2eq.framework.rest.usage;

import java.time.Duration;
import java.util.Objects;

/** Typed admission contract used by REST enforcement and observation consumers. */
public record UsageAdmissionDecision(
        UsageAdmissionDisposition disposition,
        String policyId,
        String policyVersion,
        long remainingTokens,
        Duration retryAfter,
        String stateErrorCode) {

    public UsageAdmissionDecision {
        Objects.requireNonNull(disposition, "disposition");
        if ((disposition == UsageAdmissionDisposition.REJECTED
                || disposition == UsageAdmissionDisposition.WOULD_REJECT)
                && (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero())) {
            throw new IllegalArgumentException("rejected decisions require a positive retryAfter");
        }
        if (disposition == UsageAdmissionDisposition.ENFORCEMENT_ERROR
                && (stateErrorCode == null || stateErrorCode.isBlank())) {
            throw new IllegalArgumentException("enforcement errors require stateErrorCode");
        }
    }

    public static UsageAdmissionDecision bypassed() {
        return new UsageAdmissionDecision(UsageAdmissionDisposition.BYPASSED, null, null, -1, null, null);
    }

    public static UsageAdmissionDecision admitted(UsagePolicy policy, long remainingTokens) {
        return new UsageAdmissionDecision(
                UsageAdmissionDisposition.ADMITTED, policy.id(), policy.version(), remainingTokens, null, null);
    }

    public static UsageAdmissionDecision capacityBypassed(UsagePolicy policy) {
        return new UsageAdmissionDecision(
                UsageAdmissionDisposition.CAPACITY_BYPASSED,
                policy.id(),
                policy.version(),
                -1,
                null,
                null);
    }

    public static UsageAdmissionDecision exhausted(
            UsageGovernanceMode mode, UsagePolicy policy, long retryNanos) {
        Duration retry = Duration.ofNanos(Math.max(1, retryNanos));
        UsageAdmissionDisposition disposition = mode == UsageGovernanceMode.ENFORCE
                ? UsageAdmissionDisposition.REJECTED
                : UsageAdmissionDisposition.WOULD_REJECT;
        return new UsageAdmissionDecision(disposition, policy.id(), policy.version(), 0, retry, null);
    }

    public static UsageAdmissionDecision enforcementError(String code) {
        return new UsageAdmissionDecision(
                UsageAdmissionDisposition.ENFORCEMENT_ERROR, null, null, -1, null, code);
    }
}
