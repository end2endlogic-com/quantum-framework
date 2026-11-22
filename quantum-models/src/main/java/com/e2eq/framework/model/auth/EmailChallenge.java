package com.e2eq.framework.model.auth;

import com.e2eq.framework.model.messaging.MessageChannel;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable state passed through the authority's atomic challenge repository.
 *
 * <p>Channel-neutral: {@code channel} and {@code recipient} say where the secret was sent.
 * For {@link MessageChannel#EMAIL} the recipient is the address and {@code email} carries
 * the same value, so email callers keep reading {@link #email()}. For other channels
 * {@code email} is {@code null}. A challenge stored before these fields existed has no
 * channel or recipient and reads back as an email challenge for its {@code email}.
 *
 * <p>{@code redirect} is the destination the authority already checked when it created the
 * challenge; the client never carries it. It is optional.
 */
public record EmailChallenge(
        String id, String email, String applicationId, Purpose purpose,
        String secretDigest, String clientBindingDigest, Instant expiresAt,
        int attempts, int maxAttempts, State state, long revision,
        MessageChannel channel, String recipient, String redirect) {
    /**
     * What a challenge proves the address for. A challenge redeems only for its own
     * purpose: PASSWORD_SETUP (an emailed set-password link) never signs anyone in,
     * and a sign-in link never sets a password. PHONE_VERIFICATION (a code sent to a phone a
     * signed-in person is adding) is bound to that person and never signs anyone in.
     */
    public enum Purpose {
        CLI_SIGN_IN, PORTAL_SIGN_IN, PASSWORD_SETUP, PHONE_VERIFICATION;

        /** Whether redeeming needs the client binding the challenge was created with. */
        public boolean bound() { return this == CLI_SIGN_IN || this == PHONE_VERIFICATION; }
    }
    public enum State { PENDING_DELIVERY, PENDING, VERIFIED, EXPIRED, LOCKED, REVOKED }

    public EmailChallenge {
        requireText(id, "id");
        if (channel == null) channel = MessageChannel.EMAIL;
        if (channel == MessageChannel.EMAIL) {
            requireText(email, "email");
            if (recipient == null) recipient = email;
            if (!recipient.equals(email)) {
                throw new IllegalArgumentException("recipient must equal email for an email challenge");
            }
        } else if (email != null) {
            throw new IllegalArgumentException("email is set only for an email challenge");
        }
        requireText(recipient, "recipient");
        requireText(applicationId, "applicationId");
        requireText(secretDigest, "secretDigest");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(state, "state");
        if (redirect != null && redirect.isBlank()) redirect = null;
        if (attempts < 0 || maxAttempts < 1 || attempts > maxAttempts || revision < 0) {
            throw new IllegalArgumentException("Invalid challenge counters");
        }
        if (purpose.bound()) requireText(clientBindingDigest, "clientBindingDigest");
        if (purpose == Purpose.PHONE_VERIFICATION && channel == MessageChannel.EMAIL) {
            throw new IllegalArgumentException("a phone verification is sent to a phone");
        }
    }

    /** Email challenge without a redirect: the constructor every caller used before channels. */
    public EmailChallenge(
            String id, String email, String applicationId, Purpose purpose,
            String secretDigest, String clientBindingDigest, Instant expiresAt,
            int attempts, int maxAttempts, State state, long revision) {
        this(id, email, applicationId, purpose, secretDigest, clientBindingDigest, expiresAt,
                attempts, maxAttempts, state, revision, MessageChannel.EMAIL, email, null);
    }

    public EmailChallenge transition(State next, int nextAttempts) {
        return new EmailChallenge(id, email, applicationId, purpose, secretDigest,
                clientBindingDigest, expiresAt, nextAttempts, maxAttempts, next, revision + 1,
                channel, recipient, redirect);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    /** Never expose secret digests, recipients or redirects through default logging. */
    @Override public String toString() {
        return "EmailChallenge[id=" + id + ", channel=" + channel + ", purpose=" + purpose + ", state=" + state + "]";
    }
}
