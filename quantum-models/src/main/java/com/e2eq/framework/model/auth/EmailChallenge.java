package com.e2eq.framework.model.auth;

import java.time.Instant;
import java.util.Objects;

/** Immutable state passed through the authority's atomic challenge repository. */
public record EmailChallenge(
        String id, String email, String applicationId, Purpose purpose,
        String secretDigest, String clientBindingDigest, Instant expiresAt,
        int attempts, int maxAttempts, State state, long revision) {
    public enum Purpose { CLI_SIGN_IN, PORTAL_SIGN_IN }
    public enum State { PENDING_DELIVERY, PENDING, VERIFIED, EXPIRED, LOCKED, REVOKED }

    public EmailChallenge {
        requireText(id, "id");
        requireText(email, "email");
        requireText(applicationId, "applicationId");
        requireText(secretDigest, "secretDigest");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(state, "state");
        if (attempts < 0 || maxAttempts < 1 || attempts > maxAttempts || revision < 0) {
            throw new IllegalArgumentException("Invalid challenge counters");
        }
        if (purpose == Purpose.CLI_SIGN_IN) requireText(clientBindingDigest, "clientBindingDigest");
    }

    public EmailChallenge transition(State next, int nextAttempts) {
        return new EmailChallenge(id, email, applicationId, purpose, secretDigest,
                clientBindingDigest, expiresAt, nextAttempts, maxAttempts, next, revision + 1);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    /** Never expose secret digests or email addresses through default logging. */
    @Override public String toString() {
        return "EmailChallenge[id=" + id + ", purpose=" + purpose + ", state=" + state + "]";
    }
}
