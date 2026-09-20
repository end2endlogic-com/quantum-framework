package com.e2eq.framework.service;

import com.e2eq.framework.model.auth.EmailChallenge;
import com.e2eq.framework.model.auth.EmailChallengeRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Purpose-bound, single-use proof verification; provisioning and token issuance remain separate. */
public final class EmailChallengeVerifier {
    public enum Failure { INVALID, EXPIRED, CONSUMED, LOCKED, CONCURRENT_UPDATE }

    public static final class Rejected extends RuntimeException {
        private final Failure failure;
        public Rejected(Failure failure) {
            super("EMAIL_CHALLENGE_" + failure.name());
            this.failure = failure;
        }
        public Failure failure() { return failure; }
    }

    private final EmailChallengeRepository repository;
    private final Clock clock;
    private final byte[] key;

    public EmailChallengeVerifier(EmailChallengeRepository repository, Clock clock, byte[] key) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("A managed challenge key of at least 32 bytes is required");
        }
        this.key = key.clone();
    }

    public EmailChallenge verify(String id, String applicationId, EmailChallenge.Purpose purpose,
                                 String secret, String clientVerifier) {
        if (id == null || applicationId == null || purpose == null || secret == null || secret.length() > 256) {
            throw new Rejected(Failure.INVALID);
        }
        EmailChallenge current = repository.find(id).orElseThrow(() -> new Rejected(Failure.INVALID));
        if (!current.applicationId().equals(applicationId) || current.purpose() != purpose) {
            throw new Rejected(Failure.INVALID);
        }
        if (current.state() == EmailChallenge.State.LOCKED) throw new Rejected(Failure.LOCKED);
        if (current.state() == EmailChallenge.State.EXPIRED) throw new Rejected(Failure.EXPIRED);
        if (current.state() != EmailChallenge.State.PENDING) throw new Rejected(Failure.CONSUMED);
        if (current.attempts() >= current.maxAttempts()) throw new Rejected(Failure.LOCKED);
        if (!clock.instant().isBefore(current.expiresAt())) {
            replace(current, current.transition(EmailChallenge.State.EXPIRED, current.attempts()));
            throw new Rejected(Failure.EXPIRED);
        }
        boolean bound = purpose != EmailChallenge.Purpose.CLI_SIGN_IN
                || constantEquals(current.clientBindingDigest(), bindingDigest(clientVerifier));
        boolean correct = constantEquals(current.secretDigest(), digest(id, purpose, secret));
        int attempts = Math.min(current.attempts() + 1, current.maxAttempts());
        if (!bound || !correct) {
            boolean locked = attempts >= current.maxAttempts();
            replace(current, current.transition(locked ? EmailChallenge.State.LOCKED : EmailChallenge.State.PENDING, attempts));
            throw new Rejected(locked ? Failure.LOCKED : Failure.INVALID);
        }
        EmailChallenge verified = current.transition(EmailChallenge.State.VERIFIED, attempts);
        replace(current, verified);
        return verified;
    }

    public String digest(String id, EmailChallenge.Purpose purpose, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal((purpose.name() + ":" + id + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Challenge HMAC unavailable", e);
        }
    }

    public static String bindingDigest(String verifier) {
        if (verifier == null || verifier.length() < 43 || verifier.length() > 128) return "";
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Challenge binding unavailable", e);
        }
    }

    private void replace(EmailChallenge expected, EmailChallenge replacement) {
        if (!repository.compareAndSet(expected, replacement)) throw new Rejected(Failure.CONCURRENT_UPDATE);
    }

    private static boolean constantEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
