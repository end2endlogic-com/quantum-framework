package com.e2eq.framework.model.auth.provider.jwtToken;

/**
 * How long a refresh token lives, apart from the access token it comes with.
 *
 * <p>A refresh token that expires with its access token can never be used: a client only
 * refreshes once the access token is (nearly) gone. So the refresh token gets its own,
 * longer lifetime, and each refresh issues a new one. A session kept alive by refreshing
 * still ends {@code maxSessionSeconds} after sign-in ({@code auth_time}); the last refresh
 * token is cut short to that point rather than overrunning it.</p>
 *
 * @param accessSeconds     the access token's lifetime
 * @param refreshSeconds    the refresh token's own lifetime; never shorter than the access token's
 * @param maxSessionSeconds longest a session may be refreshed, counted from sign-in
 */
public record RefreshTokenLifetime(long accessSeconds, long refreshSeconds, long maxSessionSeconds) {

    public static final long DEFAULT_REFRESH_SECONDS = 7L * 24 * 60 * 60;
    public static final long DEFAULT_MAX_SESSION_SECONDS = 30L * 24 * 60 * 60;

    public RefreshTokenLifetime {
        if (accessSeconds <= 0) {
            throw new IllegalArgumentException("Access-token duration must be greater than zero seconds");
        }
        // A refresh token that dies before its access token is useless; keep the old overhang as the floor.
        refreshSeconds = Math.max(refreshSeconds, accessSeconds + TokenUtils.REFRESH_ADDITIONAL_DURATION_SECONDS);
        if (maxSessionSeconds < refreshSeconds) {
            throw new IllegalArgumentException("Maximum session duration (" + maxSessionSeconds
                + "s) must be at least the refresh-token duration (" + refreshSeconds + "s)");
        }
    }

    /** Configured values, where {@code null} keeps the default. */
    public static RefreshTokenLifetime of(long accessSeconds, Long refreshSeconds, Long maxSessionSeconds) {
        return new RefreshTokenLifetime(accessSeconds,
            refreshSeconds == null ? DEFAULT_REFRESH_SECONDS : refreshSeconds,
            maxSessionSeconds == null ? DEFAULT_MAX_SESSION_SECONDS : maxSessionSeconds);
    }

    /**
     * Seconds the next refresh token may live, from {@code nowSeconds}, for a session that
     * signed in at {@code authTimeSeconds} ({@code null} when the token predates
     * {@code auth_time}: then {@code issuedAtSeconds} stands in). Zero or less means the
     * session has reached its maximum length and must sign in again.
     */
    public long remainingSeconds(Long authTimeSeconds, long issuedAtSeconds, long nowSeconds) {
        long start = authTimeSeconds != null ? authTimeSeconds : issuedAtSeconds;
        long left = start + maxSessionSeconds - nowSeconds;
        return Math.min(refreshSeconds, left);
    }
}
