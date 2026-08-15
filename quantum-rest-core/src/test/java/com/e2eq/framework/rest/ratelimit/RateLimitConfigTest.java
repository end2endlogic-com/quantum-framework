package com.e2eq.framework.rest.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitConfigTest {

    @Test
    void defaultsToOffAndDoesNotParseDormantValues() {
        RateLimitConfig config = new RateLimitConfig(Map.of(
                RateLimitConfig.LEGACY_REQUEST_LIMIT, "not-a-number",
                RateLimitConfig.LEGACY_REFILL_SECONDS, "also-invalid"));

        assertEquals(RateLimitMode.OFF, config.mode());
        assertFalse(config.active());
        assertEquals(1_000L, config.requestLimit());
        assertEquals(Duration.ofSeconds(5), config.refillPeriod());
    }

    @Test
    void readsPreservedLegacyProperties() {
        RateLimitConfig config = new RateLimitConfig(Map.of(
                RateLimitConfig.MODE, "enforce",
                RateLimitConfig.LEGACY_REQUEST_LIMIT, "300",
                RateLimitConfig.LEGACY_REFILL_SECONDS, "2"));

        assertTrue(config.active());
        assertEquals(300L, config.requestLimit());
        assertEquals(Duration.ofSeconds(2), config.refillPeriod());
    }

    @Test
    void canonicalPropertiesOverrideLegacyProperties() {
        Map<String, String> values = new HashMap<>();
        values.put(RateLimitConfig.MODE, "MONITOR");
        values.put(RateLimitConfig.REQUEST_LIMIT, "20");
        values.put(RateLimitConfig.LEGACY_REQUEST_LIMIT, "999");
        values.put(RateLimitConfig.REFILL_SECONDS, "7");
        values.put(RateLimitConfig.LEGACY_REFILL_SECONDS, "111");

        RateLimitConfig config = new RateLimitConfig(values);

        assertEquals(RateLimitMode.MONITOR, config.mode());
        assertEquals(20L, config.requestLimit());
        assertEquals(Duration.ofSeconds(7), config.refillPeriod());
    }

    @Test
    void activeModeFailsClosedOnInvalidCapacity() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                new RateLimitConfig(Map.of(
                        RateLimitConfig.MODE, "ENFORCE",
                        RateLimitConfig.REQUEST_LIMIT, "0")));

        assertTrue(exception.getMessage().contains(RateLimitConfig.REQUEST_LIMIT));
    }

    @Test
    void activeModeFailsClosedOnInvalidRefill() {
        assertThrows(IllegalStateException.class, () -> new RateLimitConfig(Map.of(
                RateLimitConfig.MODE, "MONITOR",
                RateLimitConfig.LEGACY_REFILL_SECONDS, "invalid")));
    }

    @Test
    void unknownModeAlwaysFailsClosed() {
        assertThrows(IllegalStateException.class, () ->
                new RateLimitConfig(Map.of(RateLimitConfig.MODE, "enabled")));
    }

    @Test
    void validatesForwardingOnlyWhenExplicitlyEnabled() {
        RateLimitConfig disabledForwarding = new RateLimitConfig(Map.of(
                RateLimitConfig.MODE, "ENFORCE",
                RateLimitConfig.TRUSTED_PROXY_HOPS, "invalid"));
        assertFalse(disabledForwarding.forwardedEnabled());

        assertThrows(IllegalStateException.class, () -> new RateLimitConfig(Map.of(
                RateLimitConfig.MODE, "ENFORCE",
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "10.0.0.0/8",
                RateLimitConfig.TRUSTED_PROXY_HOPS, "0")));
    }

    @Test
    void forwardedIdentityRequiresAValidTrustedPeerAllowlist() {
        assertThrows(IllegalStateException.class, () -> new RateLimitConfig(Map.of(
                RateLimitConfig.MODE, "ENFORCE",
                RateLimitConfig.FORWARDED_ENABLED, "true")));
        assertThrows(IllegalStateException.class, () -> new RateLimitConfig(Map.of(
                RateLimitConfig.MODE, "ENFORCE",
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "not-a-network")));

        RateLimitConfig config = new RateLimitConfig(Map.of(
                RateLimitConfig.MODE, "ENFORCE",
                RateLimitConfig.FORWARDED_ENABLED, "true",
                RateLimitConfig.FORWARDED_TRUSTED_PEERS, "10.0.0.0/8, 2001:db8::/32"));
        assertTrue(config.isTrustedForwardedPeer("10.4.5.6"));
        assertTrue(config.isTrustedForwardedPeer("2001:db8:0:0:0:0:0:8"));
        assertFalse(config.isTrustedForwardedPeer("192.0.2.1"));
    }
}
