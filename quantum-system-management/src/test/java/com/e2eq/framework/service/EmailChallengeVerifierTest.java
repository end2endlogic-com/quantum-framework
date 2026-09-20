package com.e2eq.framework.service;

import com.e2eq.framework.model.auth.EmailChallenge;
import com.e2eq.framework.model.auth.EmailChallengeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmailChallengeVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final String BINDING = "a".repeat(43);
    private static final class Store implements EmailChallengeRepository {
        final AtomicReference<EmailChallenge> value = new AtomicReference<>();
        public Optional<EmailChallenge> find(String id) {
            return Optional.ofNullable(value.get()).filter(c -> c.id().equals(id));
        }
        public boolean compareAndSet(EmailChallenge expected, EmailChallenge replacement) {
            return value.compareAndSet(expected, replacement);
        }
    }
    private EmailChallengeVerifier verifier(Store store, Instant now) {
        return new EmailChallengeVerifier(store, Clock.fixed(now, ZoneOffset.UTC), new byte[32]);
    }
    private Store pending(EmailChallenge.Purpose purpose) {
        Store store = new Store();
        var verifier = verifier(store, NOW);
        store.value.set(new EmailChallenge("challenge", "user@example.test", "test-app", purpose,
                verifier.digest("challenge", purpose, "012345"), EmailChallengeVerifier.bindingDigest(BINDING),
                NOW.plusSeconds(300), 0, 5, EmailChallenge.State.PENDING, 0));
        return store;
    }
    @Test void acceptsLeadingZeroCodeOnce() {
        Store store = pending(EmailChallenge.Purpose.CLI_SIGN_IN);
        var verifier = verifier(store, NOW);
        assertEquals(EmailChallenge.State.VERIFIED,
                verifier.verify("challenge", "test-app", EmailChallenge.Purpose.CLI_SIGN_IN, "012345", BINDING).state());
        assertEquals(EmailChallengeVerifier.Failure.CONSUMED, assertThrows(EmailChallengeVerifier.Rejected.class,
                () -> verifier.verify("challenge", "test-app", EmailChallenge.Purpose.CLI_SIGN_IN, "012345", BINDING)).failure());
    }
    @Test void rejectsAtExactFiveMinuteBoundary() {
        Store store = pending(EmailChallenge.Purpose.CLI_SIGN_IN);
        assertEquals(EmailChallengeVerifier.Failure.EXPIRED, assertThrows(EmailChallengeVerifier.Rejected.class,
                () -> verifier(store, NOW.plusSeconds(300)).verify("challenge", "test-app", EmailChallenge.Purpose.CLI_SIGN_IN, "012345", BINDING)).failure());
    }
    @Test void fifthFailedAttemptLocksEvenWhenNextCodeIsCorrect() {
        Store store = pending(EmailChallenge.Purpose.CLI_SIGN_IN);
        var verifier = verifier(store, NOW);
        for (int i = 0; i < 5; i++) assertThrows(EmailChallengeVerifier.Rejected.class,
                () -> verifier.verify("challenge", "test-app", EmailChallenge.Purpose.CLI_SIGN_IN, "999999", BINDING));
        assertEquals(EmailChallengeVerifier.Failure.LOCKED, assertThrows(EmailChallengeVerifier.Rejected.class,
                () -> verifier.verify("challenge", "test-app", EmailChallenge.Purpose.CLI_SIGN_IN, "012345", BINDING)).failure());
    }
    @Test void rejectsWrongApplicationPurposeAndBinding() {
        Store store = pending(EmailChallenge.Purpose.CLI_SIGN_IN);
        var verifier = verifier(store, NOW);
        assertThrows(EmailChallengeVerifier.Rejected.class, () -> verifier.verify("challenge", "other-app", EmailChallenge.Purpose.CLI_SIGN_IN, "012345", BINDING));
        assertThrows(EmailChallengeVerifier.Rejected.class, () -> verifier.verify("challenge", "test-app", EmailChallenge.Purpose.PORTAL_SIGN_IN, "012345", null));
        assertThrows(EmailChallengeVerifier.Rejected.class, () -> verifier.verify("challenge", "test-app", EmailChallenge.Purpose.CLI_SIGN_IN, "012345", "b".repeat(43)));
        assertEquals(1, store.value.get().attempts());
    }
    @Test void concurrentConsumptionCannotReturnVerifiedProof() {
        Store store = pending(EmailChallenge.Purpose.PORTAL_SIGN_IN);
        EmailChallengeRepository losingWriter = new EmailChallengeRepository() {
            public Optional<EmailChallenge> find(String id) { return store.find(id); }
            public boolean compareAndSet(EmailChallenge expected, EmailChallenge replacement) { return false; }
        };
        var verifier = new EmailChallengeVerifier(losingWriter, Clock.fixed(NOW, ZoneOffset.UTC), new byte[32]);
        assertEquals(EmailChallengeVerifier.Failure.CONCURRENT_UPDATE, assertThrows(EmailChallengeVerifier.Rejected.class,
                () -> verifier.verify("challenge", "test-app", EmailChallenge.Purpose.PORTAL_SIGN_IN, "012345", null)).failure());
    }
    @Test void requiresManagedKeyAndRedactsLogging() {
        assertThrows(IllegalArgumentException.class, () -> new EmailChallengeVerifier(new Store(), Clock.systemUTC(), new byte[0]));
        assertFalse(pending(EmailChallenge.Purpose.PORTAL_SIGN_IN).value.get().toString().contains("user@example.test"));
    }
}
