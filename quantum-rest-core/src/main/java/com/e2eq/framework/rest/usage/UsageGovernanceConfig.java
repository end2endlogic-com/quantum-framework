package com.e2eq.framework.rest.usage;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Validated property-backed usage manifest. OFF deliberately leaves dormant policy values unparsed. */
@Startup
@ApplicationScoped
public class UsageGovernanceConfig {

    public static final String PREFIX = "quantum.rest.usage.";
    public static final String MODE = PREFIX + "mode";
    public static final String POLICY_ID = PREFIX + "policy.id";
    public static final String POLICY_VERSION = PREFIX + "policy.version";
    public static final String POLICY_REQUEST_LIMIT = PREFIX + "policy.request-limit";
    public static final String POLICY_REFILL_PERIOD = PREFIX + "policy.refill-period";
    public static final String POLICY_ENDPOINTS = PREFIX + "policy.endpoints";
    public static final String ALLOW_UNMATCHED_ENDPOINTS = PREFIX + "allow-unmatched-endpoints";
    public static final String MAX_TRACKED_KEYS = PREFIX + "bucket.max-tracked-keys";
    public static final String IDLE_TIMEOUT = PREFIX + "bucket.idle-timeout";
    public static final String OPENAPI_OPERATION_ID_STRATEGY =
            "mp.openapi.extensions.smallrye.operationIdStrategy";

    private static final String DEFAULT_POLICY_ID = "default-rest-usage";
    private static final String DEFAULT_POLICY_VERSION = "1";
    private static final long DEFAULT_REQUEST_LIMIT = 1_000L;
    private static final Duration DEFAULT_REFILL_PERIOD = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_TRACKED_KEYS = 10_000;
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);
    private static final boolean DEFAULT_ALLOW_UNMATCHED_ENDPOINTS = true;

    private final UsageGovernanceMode mode;
    private final UsagePolicy policy;
    private final int maxTrackedKeys;
    private final Duration idleTimeout;
    private final boolean allowUnmatchedEndpoints;

    @Inject
    public UsageGovernanceConfig() {
        this(configLookup(ConfigProvider.getConfig()));
    }

    UsageGovernanceConfig(Map<String, String> values) {
        this(values::get);
    }

    private UsageGovernanceConfig(Function<String, String> lookup) {
        mode = UsageGovernanceMode.parse(lookup.apply(MODE));
        if (mode == UsageGovernanceMode.OFF) {
            policy = null;
            maxTrackedKeys = DEFAULT_MAX_TRACKED_KEYS;
            idleTimeout = DEFAULT_IDLE_TIMEOUT;
            allowUnmatchedEndpoints = DEFAULT_ALLOW_UNMATCHED_ENDPOINTS;
            return;
        }

        String operationIdStrategy = valueOrDefault(
                lookup.apply(OPENAPI_OPERATION_ID_STRATEGY), "");
        if (!"METHOD".equalsIgnoreCase(operationIdStrategy)) {
            throw new IllegalStateException(
                    "Active usage governance requires " + OPENAPI_OPERATION_ID_STRATEGY
                            + "=METHOD so generated and runtime operation identities are identical");
        }

        String policyId = valueOrDefault(lookup.apply(POLICY_ID), DEFAULT_POLICY_ID);
        String policyVersion = valueOrDefault(lookup.apply(POLICY_VERSION), DEFAULT_POLICY_VERSION);
        long requestLimit = positiveLong(
                POLICY_REQUEST_LIMIT, lookup.apply(POLICY_REQUEST_LIMIT), DEFAULT_REQUEST_LIMIT);
        Duration refillPeriod = positiveDuration(
                POLICY_REFILL_PERIOD, lookup.apply(POLICY_REFILL_PERIOD), DEFAULT_REFILL_PERIOD);
        Set<String> endpoints = endpointSelectors(lookup.apply(POLICY_ENDPOINTS));
        maxTrackedKeys = positiveInt(
                MAX_TRACKED_KEYS, lookup.apply(MAX_TRACKED_KEYS), DEFAULT_MAX_TRACKED_KEYS);
        idleTimeout = positiveDuration(IDLE_TIMEOUT, lookup.apply(IDLE_TIMEOUT), DEFAULT_IDLE_TIMEOUT);
        allowUnmatchedEndpoints = strictBoolean(
                ALLOW_UNMATCHED_ENDPOINTS,
                lookup.apply(ALLOW_UNMATCHED_ENDPOINTS),
                DEFAULT_ALLOW_UNMATCHED_ENDPOINTS);
        try {
            policy = new UsagePolicy(policyId, policyVersion, requestLimit, refillPeriod, endpoints);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid usage policy configuration: " + exception.getMessage(), exception);
        }
    }

    public UsageGovernanceMode mode() {
        return mode;
    }

    public boolean active() {
        return mode != UsageGovernanceMode.OFF;
    }

    public UsagePolicy policy() {
        if (policy == null) {
            throw new IllegalStateException("No usage policy exists while usage governance is OFF");
        }
        return policy;
    }

    public int maxTrackedKeys() {
        return maxTrackedKeys;
    }

    public Duration idleTimeout() {
        return idleTimeout;
    }

    public boolean allowUnmatchedEndpoints() {
        return allowUnmatchedEndpoints;
    }

    private static Function<String, String> configLookup(Config config) {
        return key -> config.getOptionalValue(key, String.class).orElse(null);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static long positiveLong(String key, String value, long defaultValue) {
        long parsed;
        try {
            parsed = value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(key + " must be a positive integer", exception);
        }
        if (parsed <= 0) {
            throw new IllegalStateException(key + " must be greater than zero");
        }
        return parsed;
    }

    private static int positiveInt(String key, String value, int defaultValue) {
        long parsed = positiveLong(key, value, defaultValue);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalStateException(key + " must be at most " + Integer.MAX_VALUE);
        }
        return (int) parsed;
    }

    private static Duration positiveDuration(String key, String value, Duration defaultValue) {
        Duration parsed;
        try {
            parsed = value == null || value.isBlank() ? defaultValue : Duration.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(key + " must be an ISO-8601 duration", exception);
        }
        if (parsed.isZero() || parsed.isNegative()) {
            throw new IllegalStateException(key + " must be greater than zero");
        }
        try {
            parsed.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(key + " is too large", exception);
        }
        return parsed;
    }

    private static boolean strictBoolean(String key, String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalStateException(key + " must be true or false");
    }

    private static Set<String> endpointSelectors(String value) {
        if (value == null || value.isBlank()) {
            return Set.of(UsagePolicy.ALL_ENDPOINTS);
        }
        LinkedHashSet<String> selectors = new LinkedHashSet<>();
        Arrays.stream(value.split(",", -1)).forEach(raw -> {
            String selector = raw.trim();
            if (selector.isEmpty()) {
                throw new IllegalStateException(POLICY_ENDPOINTS + " contains an empty selector");
            }
            try {
                selectors.add(UsagePolicy.ALL_ENDPOINTS.equals(selector)
                        ? UsagePolicy.ALL_ENDPOINTS
                        : UsageEndpointIdentity.parse(selector).canonicalName());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        POLICY_ENDPOINTS + " contains an invalid selector '" + selector + "'", exception);
            }
        });
        return Set.copyOf(selectors);
    }
}
