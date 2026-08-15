package com.e2eq.framework.rest.ratelimit;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Validated, manifest/property-driven rate-limit configuration.
 *
 * <p>The limiter is inert by default. Dormant values are deliberately not
 * parsed in {@link RateLimitMode#OFF} mode, while every value used by an
 * active mode is validated during application startup.</p>
 */
@Startup
@ApplicationScoped
public class RateLimitConfig {

    private static final Logger LOG = Logger.getLogger(RateLimitConfig.class);

    public static final String MODE = "quantum.rest.rate-limit.mode";
    public static final String REQUEST_LIMIT = "quantum.rest.rate-limit.request.limit";
    public static final String REFILL_SECONDS = "quantum.rest.rate-limit.refill.seconds";
    public static final String LEGACY_REQUEST_LIMIT = "rate.limit.request.limit";
    public static final String LEGACY_REFILL_SECONDS = "rate.limit.refill.seconds";
    public static final String MAX_TRACKED_CLIENTS = "quantum.rest.rate-limit.max-tracked-clients";
    public static final String OVERFLOW_SHARDS = "quantum.rest.rate-limit.overflow-shards";
    public static final String IDLE_SECONDS = "quantum.rest.rate-limit.idle.seconds";
    public static final String FORWARDED_ENABLED = "quantum.rest.rate-limit.forwarded.enabled";
    public static final String TRUSTED_PROXY_HOPS = "quantum.rest.rate-limit.forwarded.trusted-proxy-hops";
    public static final String FORWARDED_TRUSTED_PEERS = "quantum.rest.rate-limit.forwarded.trusted-peers";
    public static final String FORWARDED_IPV4_PREFIX = "quantum.rest.rate-limit.forwarded.ipv4-prefix-length";
    public static final String FORWARDED_IPV6_PREFIX = "quantum.rest.rate-limit.forwarded.ipv6-prefix-length";

    private static final long DEFAULT_REQUEST_LIMIT = 1_000L;
    private static final long DEFAULT_REFILL_SECONDS = 5L;
    private static final int DEFAULT_MAX_TRACKED_CLIENTS = 10_000;
    private static final int DEFAULT_OVERFLOW_SHARDS = 64;
    private static final long DEFAULT_IDLE_SECONDS = 600L;
    private static final int MAX_TRACKED_CLIENT_LIMIT = 1_000_000;
    private static final long MAX_DURATION_SECONDS = Duration.ofDays(365).getSeconds();
    private static final int MAX_TRUSTED_PROXY_HOPS = 16;

    @Inject
    Config config;

    private RateLimitMode mode = RateLimitMode.OFF;
    private long requestLimit = DEFAULT_REQUEST_LIMIT;
    private Duration refillPeriod = Duration.ofSeconds(DEFAULT_REFILL_SECONDS);
    private int maxTrackedClients = DEFAULT_MAX_TRACKED_CLIENTS;
    private int overflowShards = DEFAULT_OVERFLOW_SHARDS;
    private Duration idleTimeout = Duration.ofSeconds(DEFAULT_IDLE_SECONDS);
    private boolean forwardedEnabled;
    private int trustedProxyHops = 1;
    private List<TrustedPeerNetwork> trustedForwardedPeers = List.of();
    private int forwardedIpv4PrefixLength = 24;
    private int forwardedIpv6PrefixLength = 64;

    public RateLimitConfig() {
        // CDI constructor.
    }

    RateLimitConfig(Map<String, String> values) {
        load(name -> Optional.ofNullable(values.get(name)));
    }

    @PostConstruct
    void initialize() {
        load(name -> config.getOptionalValue(name, String.class));
        if (active() && !forwardedEnabled) {
            LOG.warn("Quantum REST rate limiting is using direct-peer identity. "
                    + "If requests arrive through a shared proxy, all clients behind it share one bucket; "
                    + "configure trusted forwarded peers before enabling forwarded identity.");
        }
    }

    private void load(Function<String, Optional<String>> lookup) {
        mode = RateLimitMode.parse(lookup.apply(MODE).orElse("OFF"));

        requestLimit = DEFAULT_REQUEST_LIMIT;
        refillPeriod = Duration.ofSeconds(DEFAULT_REFILL_SECONDS);
        maxTrackedClients = DEFAULT_MAX_TRACKED_CLIENTS;
        overflowShards = DEFAULT_OVERFLOW_SHARDS;
        idleTimeout = Duration.ofSeconds(DEFAULT_IDLE_SECONDS);
        forwardedEnabled = false;
        trustedProxyHops = 1;
        trustedForwardedPeers = List.of();
        forwardedIpv4PrefixLength = 24;
        forwardedIpv6PrefixLength = 64;

        if (mode == RateLimitMode.OFF) {
            return;
        }

        requestLimit = positiveLong(
                first(lookup, REQUEST_LIMIT, LEGACY_REQUEST_LIMIT, Long.toString(DEFAULT_REQUEST_LIMIT)),
                REQUEST_LIMIT + " (legacy fallback: " + LEGACY_REQUEST_LIMIT + ")",
                Long.MAX_VALUE);
        long refillSeconds = positiveLong(
                first(lookup, REFILL_SECONDS, LEGACY_REFILL_SECONDS, Long.toString(DEFAULT_REFILL_SECONDS)),
                REFILL_SECONDS + " (legacy fallback: " + LEGACY_REFILL_SECONDS + ")",
                MAX_DURATION_SECONDS);
        refillPeriod = Duration.ofSeconds(refillSeconds);

        maxTrackedClients = (int) positiveLong(
                first(lookup, MAX_TRACKED_CLIENTS, null, Integer.toString(DEFAULT_MAX_TRACKED_CLIENTS)),
                MAX_TRACKED_CLIENTS,
                MAX_TRACKED_CLIENT_LIMIT);
        overflowShards = (int) positiveLong(
                first(lookup, OVERFLOW_SHARDS, null, Integer.toString(DEFAULT_OVERFLOW_SHARDS)),
                OVERFLOW_SHARDS,
                1_024);
        long idleSeconds = positiveLong(
                first(lookup, IDLE_SECONDS, null, Long.toString(DEFAULT_IDLE_SECONDS)),
                IDLE_SECONDS,
                MAX_DURATION_SECONDS);
        idleTimeout = Duration.ofSeconds(idleSeconds);

        forwardedEnabled = strictBoolean(
                first(lookup, FORWARDED_ENABLED, null, "false"),
                FORWARDED_ENABLED);
        if (forwardedEnabled) {
            trustedProxyHops = (int) positiveLong(
                    first(lookup, TRUSTED_PROXY_HOPS, null, "1"),
                    TRUSTED_PROXY_HOPS,
                    MAX_TRUSTED_PROXY_HOPS);
            String trustedPeers = first(lookup, FORWARDED_TRUSTED_PEERS, null, "");
            if (trustedPeers.isBlank()) {
                throw invalid(
                        FORWARDED_TRUSTED_PEERS,
                        trustedPeers,
                        "one or more comma-separated IP literals or CIDR ranges when forwarded identity is enabled",
                        null);
            }
            try {
                trustedForwardedPeers = Arrays.stream(trustedPeers.split(",", -1))
                        .map(String::trim)
                        .map(TrustedPeerNetwork::parse)
                        .toList();
            } catch (RuntimeException exception) {
                throw invalid(
                        FORWARDED_TRUSTED_PEERS,
                        trustedPeers,
                        "one or more comma-separated IP literals or CIDR ranges",
                        exception);
            }
            forwardedIpv4PrefixLength = nonNegativeLong(
                    first(lookup, FORWARDED_IPV4_PREFIX, null, "24"),
                    FORWARDED_IPV4_PREFIX,
                    32);
            forwardedIpv6PrefixLength = nonNegativeLong(
                    first(lookup, FORWARDED_IPV6_PREFIX, null, "64"),
                    FORWARDED_IPV6_PREFIX,
                    128);
        }
    }

    private static String first(
            Function<String, Optional<String>> lookup,
            String canonical,
            String legacy,
            String defaultValue) {
        Optional<String> canonicalValue = lookup.apply(canonical);
        if (canonicalValue.isPresent()) {
            return canonicalValue.get();
        }
        if (legacy != null) {
            Optional<String> legacyValue = lookup.apply(legacy);
            if (legacyValue.isPresent()) {
                return legacyValue.get();
            }
        }
        return defaultValue;
    }

    private static long positiveLong(String value, String property, long maximum) {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0 || parsed > maximum) {
                throw new NumberFormatException("outside range");
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw invalid(property, value, "an integer from 1 through " + maximum, exception);
        }
    }

    private static boolean strictBoolean(String value, String property) {
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        throw invalid(property, value, "true or false", null);
    }

    private static int nonNegativeLong(String value, String property, int maximum) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0 || parsed > maximum) {
                throw new NumberFormatException("outside range");
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw invalid(property, value, "an integer from 0 through " + maximum, exception);
        }
    }

    private static IllegalStateException invalid(
            String property,
            String value,
            String expectation,
            Throwable cause) {
        String message = "Invalid active rate-limit configuration for '" + property
                + "': '" + value + "' (expected " + expectation + ")";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    public RateLimitMode mode() {
        return mode;
    }

    public boolean active() {
        return mode != RateLimitMode.OFF;
    }

    public long requestLimit() {
        return requestLimit;
    }

    public Duration refillPeriod() {
        return refillPeriod;
    }

    public int maxTrackedClients() {
        return maxTrackedClients;
    }

    public int overflowShards() {
        return overflowShards;
    }

    public Duration idleTimeout() {
        return idleTimeout;
    }

    public boolean forwardedEnabled() {
        return forwardedEnabled;
    }

    public int trustedProxyHops() {
        return trustedProxyHops;
    }

    boolean isTrustedForwardedPeer(String normalizedPeer) {
        return normalizedPeer != null
                && trustedForwardedPeers.stream().anyMatch(network -> network.contains(normalizedPeer));
    }

    int forwardedPrefixLength(boolean ipv6) {
        return ipv6 ? forwardedIpv6PrefixLength : forwardedIpv4PrefixLength;
    }
}
