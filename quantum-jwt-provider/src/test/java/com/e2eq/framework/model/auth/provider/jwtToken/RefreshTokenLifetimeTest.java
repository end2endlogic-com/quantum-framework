package com.e2eq.framework.model.auth.provider.jwtToken;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshTokenLifetimeTest {

    private static final long HOUR = 3600;
    private static final long DAY = 24 * HOUR;

    @Test
    void unsetValuesOutliveTheAccessToken() {
        RefreshTokenLifetime lifetime = RefreshTokenLifetime.of(8 * HOUR, null, null);

        assertEquals(RefreshTokenLifetime.DEFAULT_REFRESH_SECONDS, lifetime.refreshSeconds());
        assertEquals(RefreshTokenLifetime.DEFAULT_MAX_SESSION_SECONDS, lifetime.maxSessionSeconds());
        assertTrue(lifetime.refreshSeconds() > lifetime.accessSeconds());
    }

    @Test
    void aRefreshShorterThanTheAccessTokenIsRaisedToOutliveIt() {
        RefreshTokenLifetime lifetime = RefreshTokenLifetime.of(8 * HOUR, HOUR, 30 * DAY);

        assertEquals(8 * HOUR + TokenUtils.REFRESH_ADDITIONAL_DURATION_SECONDS, lifetime.refreshSeconds());
    }

    @Test
    void aMaximumSessionShorterThanOneRefreshIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> RefreshTokenLifetime.of(8 * HOUR, 7 * DAY, DAY));
    }

    @Test
    void aFreshSessionGetsTheFullRefreshLifetime() {
        RefreshTokenLifetime lifetime = RefreshTokenLifetime.of(8 * HOUR, 7 * DAY, 30 * DAY);
        long signedIn = 1_000_000;

        assertEquals(7 * DAY, lifetime.remainingSeconds(signedIn, signedIn, signedIn + HOUR));
    }

    @Test
    void theLastRefreshIsCutShortAtTheMaximumSession() {
        RefreshTokenLifetime lifetime = RefreshTokenLifetime.of(8 * HOUR, 7 * DAY, 30 * DAY);
        long signedIn = 1_000_000;

        assertEquals(2 * DAY, lifetime.remainingSeconds(signedIn, signedIn, signedIn + 28 * DAY));
        assertTrue(lifetime.remainingSeconds(signedIn, signedIn, signedIn + 30 * DAY) <= 0);
    }

    @Test
    void aTokenWithoutAuthTimeCountsFromItsIssue() {
        RefreshTokenLifetime lifetime = RefreshTokenLifetime.of(8 * HOUR, 7 * DAY, 30 * DAY);
        long issued = 2_000_000;

        assertEquals(DAY, lifetime.remainingSeconds(null, issued, issued + 29 * DAY));
    }
}
