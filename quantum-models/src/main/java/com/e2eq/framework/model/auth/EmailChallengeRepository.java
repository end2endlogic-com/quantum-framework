package com.e2eq.framework.model.auth;

import java.util.Optional;

/**
 * Authority-owned persistence. Implementations must scope every operation to one
 * issuer, persist across restarts, and implement compareAndSet atomically across
 * processes. A cache or read-then-unconditional-save implementation is invalid.
 */
public interface EmailChallengeRepository {
    Optional<EmailChallenge> find(String id);
    boolean compareAndSet(EmailChallenge expected, EmailChallenge replacement);
}
