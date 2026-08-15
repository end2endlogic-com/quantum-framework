package com.e2eq.framework.rest.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitClientIdentityTest {

    private final RateLimitClientIdentity identity = new RateLimitClientIdentity();

    @Test
    void spoofedForwardedHeadersAreIgnoredByDefault() {
        RateLimitConfig config = active(Map.of());

        String first = identity.resolve("198.51.100.10", "192.0.2.4", config).key();
        String second = identity.resolve("203.0.113.99", "192.0.2.4", config).key();

        assertEquals("peer:192.0.2.4", first);
        assertEquals(first, second);
    }

    @Test
    void trustedForwardingCountsHopsFromTheRight() {
        RateLimitConfig config = active(Map.of(
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "10.0.0.0/8",
                RateLimitConfig.TRUSTED_PROXY_HOPS, "2"));

        String key = identity.resolve(
                "198.51.100.7, 10.0.0.4",
                "10.0.0.5",
                config).key();

        assertEquals("forwarded:198.51.100.0/24", key);
    }

    @Test
    void prependedForwardedAddressCannotWinTheTrustedHopSelection() {
        RateLimitConfig config = active(Map.of(
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "10.0.0.0/8",
                RateLimitConfig.TRUSTED_PROXY_HOPS, "2"));

        assertEquals(
                "forwarded:198.51.100.0/24",
                identity.resolve("1.2.3.4, 198.51.100.7, 10.0.0.4", "10.0.0.5", config).key());
    }

    @Test
    void shortForwardedChainFallsBackToThePeer() {
        RateLimitConfig config = active(Map.of(
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "10.0.0.0/8",
                RateLimitConfig.TRUSTED_PROXY_HOPS, "2"));

        RateLimitClientIdentity.Resolution resolution = identity.resolve("198.51.100.7", "10.0.0.5", config);
        assertNull(resolution.key());
        assertTrue(resolution.forwardedResolutionFailed());
    }

    @Test
    void malformedTrustedForwardingFailsExplicitlyInsteadOfCollapsingToPeer() {
        RateLimitConfig config = active(Map.of(
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "192.0.2.9"));

        RateLimitClientIdentity.Resolution resolution =
                identity.resolve("attacker-controlled-name", "192.0.2.9", config);
        assertNull(resolution.key());
        assertTrue(resolution.forwardedResolutionFailed());
    }

    @Test
    void validatesIpv6LiteralsWithoutTreatingMalformedHexAsAHostName() {
        RateLimitConfig config = active(Map.of(
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "192.0.2.0/24"));

        assertEquals(
                "forwarded:2001:db8:0:0:0:0:0:0/64",
                identity.resolve("2001:db8::1", "192.0.2.9", config).key());
        RateLimitClientIdentity.Resolution malformed = identity.resolve("ab:cd", "192.0.2.9", config);
        assertNull(malformed.key());
        assertTrue(malformed.forwardedResolutionFailed());
    }

    @Test
    void normalizesPortSuffixedAndIpv4MappedProxyAddresses() {
        RateLimitConfig config = active(Map.of(
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "192.0.2.0/24"));

        assertEquals(
                "forwarded:203.0.113.0/24",
                identity.resolve("203.0.113.9:443", "192.0.2.9", config).key());
        assertEquals(
                "forwarded:203.0.113.0/24",
                identity.resolve("::ffff:203.0.113.9", "192.0.2.9", config).key());
    }

    @Test
    void untrustedPeerCannotActivateForwardedIdentity() {
        RateLimitConfig config = active(Map.of(
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "10.0.0.0/8"));

        RateLimitClientIdentity.Resolution resolution =
                identity.resolve("198.51.100.7", "192.0.2.9", config);

        assertEquals("peer:192.0.2.9", resolution.key());
        assertFalse(resolution.forwardedResolutionFailed());
    }

    @Test
    void missingIdentityUsesOneSharedAnonymousKey() {
        RateLimitConfig config = active(Map.of());

        assertEquals(RateLimitClientIdentity.ANONYMOUS, identity.resolve(null, null, config).key());
        assertEquals(RateLimitClientIdentity.ANONYMOUS, identity.resolve("anything", "bad-peer", config).key());
    }

    private static RateLimitConfig active(Map<String, String> additions) {
        java.util.HashMap<String, String> values = new java.util.HashMap<>(additions);
        values.put(RateLimitConfig.MODE, "ENFORCE");
        return new RateLimitConfig(values);
    }
}
