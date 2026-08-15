package com.e2eq.framework.rest.ratelimit;

import com.e2eq.framework.rest.models.RestError;
import io.github.bucket4j.TimeMeter;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuantumRateLimitFilterTest {

    @Test
    void providerRunsBeforeAuthenticationAndBeforeRouteMatching() {
        Priority priority = QuantumRateLimitFilter.class.getAnnotation(Priority.class);

        assertNotNull(QuantumRateLimitFilter.class.getAnnotation(PreMatching.class));
        assertNotNull(priority);
        assertEquals(Priorities.AUTHENTICATION - 100, priority.value());
    }

    @Test
    void offModeShortCircuitsBeforeIdentityBucketAndMetricsWork() throws Exception {
        Fixture fixture = fixture(Map.of(RateLimitConfig.MODE, "OFF"));
        RequestStub request = new RequestStub("198.51.100.20");

        fixture.filter.filter(request.context());

        assertNull(request.aborted);
        assertEquals(0, fixture.registry.totalBucketCount());
        assertEquals(Map.of(
                "allowed", 0L,
                "rejected", 0L,
                "wouldBeRejected", 0L,
                "overflowAssignments", 0L,
                "forwardedResolutionFailed", 0L), fixture.stats.snapshot());
    }

    @Test
    void enforceModeReturnsTyped429AndRetryAfter() throws Exception {
        Fixture fixture = fixture(active("ENFORCE", 1, 10, 100));
        RequestStub first = new RequestStub(null);
        RequestStub second = new RequestStub(null);

        fixture.filter.filter(first.context());
        fixture.filter.filter(second.context());

        assertNull(first.aborted);
        assertNotNull(second.aborted);
        assertEquals(429, second.aborted.getStatus());
        assertEquals("10", second.aborted.getHeaderString(HttpHeaders.RETRY_AFTER));
        RestError error = assertInstanceOf(RestError.class, second.aborted.getEntity());
        assertEquals(429, error.getStatus());
        assertEquals("Too Many Requests", error.getStatusMessage());
        assertNull(error.getDebugMessage());
        assertEquals("application/json;charset=UTF-8", second.aborted.getMediaType().toString());
        assertEquals(1L, fixture.stats.snapshot().get("allowed"));
        assertEquals(1L, fixture.stats.snapshot().get("rejected"));
    }

    @Test
    void monitorModeRecordsWouldBlockButAllowsTheRequest() throws Exception {
        Fixture fixture = fixture(active("MONITOR", 1, 10, 100));
        RequestStub first = new RequestStub(null);
        RequestStub second = new RequestStub(null);

        fixture.filter.filter(first.context());
        fixture.filter.filter(second.context());

        assertNull(first.aborted);
        assertNull(second.aborted);
        assertEquals(1L, fixture.stats.snapshot().get("allowed"));
        assertEquals(1L, fixture.stats.snapshot().get("wouldBeRejected"));
        assertEquals(0L, fixture.stats.snapshot().get("rejected"));
    }

    @Test
    void spoofedForwardedValuesCollapseToThePeerBucketWhenTrustIsOff() throws Exception {
        Fixture fixture = fixture(active("ENFORCE", 2_000, 60, 100));

        for (int index = 0; index < 1_000; index++) {
            RequestStub request = new RequestStub("198.51.100." + (index % 255));
            fixture.filter.filter(request.context());
        }

        assertEquals(1, fixture.registry.trackedBucketCount());
        assertEquals(1_000L, fixture.stats.snapshot().get("allowed"));
    }

    @Test
    void retryAfterDecaysAndTheRequestRecoversAfterRefill() throws Exception {
        Fixture fixture = fixture(active("ENFORCE", 1, 10, 100));
        fixture.filter.filter(new RequestStub(null).context());

        RequestStub initialRejection = new RequestStub(null);
        fixture.filter.filter(initialRejection.context());
        assertEquals("10", initialRejection.aborted.getHeaderString(HttpHeaders.RETRY_AFTER));

        fixture.time.advance(Duration.ofSeconds(5));
        RequestStub laterRejection = new RequestStub(null);
        fixture.filter.filter(laterRejection.context());
        assertEquals("5", laterRejection.aborted.getHeaderString(HttpHeaders.RETRY_AFTER));

        fixture.time.advance(Duration.ofSeconds(5));
        RequestStub recovered = new RequestStub(null);
        fixture.filter.filter(recovered.context());
        assertNull(recovered.aborted);
    }

    @Test
    void untrustedForwardedResolutionAndOverflowAreObservable() throws Exception {
        Map<String, String> values = active("ENFORCE", 10, 60, 1);
        values.put(RateLimitConfig.FORWARDED_ENABLED, "true");
        values.put(RateLimitConfig.FORWARDED_TRUSTED_PEERS, "192.0.2.44");
        Fixture fixture = fixture(values);

        fixture.filter.filter(new RequestStub("198.51.100.10").context());
        fixture.filter.filter(new RequestStub("203.0.113.20").context());

        assertEquals(1L, fixture.stats.snapshot().get("overflowAssignments"));

        Map<String, String> untrustedValues = active("ENFORCE", 10, 60, 10);
        untrustedValues.put(RateLimitConfig.FORWARDED_ENABLED, "true");
        untrustedValues.put(RateLimitConfig.FORWARDED_TRUSTED_PEERS, "10.0.0.0/8");
        Fixture untrusted = fixture(untrustedValues);
        untrusted.filter.filter(new RequestStub("198.51.100.10").context());

        assertEquals(0L, untrusted.stats.snapshot().get("forwardedResolutionFailed"));
    }

    @Test
    void malformedIdentityFromTrustedPeerIsRejectedWithoutUsingASharedProxyBucket() throws Exception {
        Map<String, String> values = active("ENFORCE", 10, 60, 10);
        values.put(RateLimitConfig.FORWARDED_ENABLED, "true");
        values.put(RateLimitConfig.FORWARDED_TRUSTED_PEERS, "192.0.2.44");
        Fixture fixture = fixture(values);
        RequestStub request = new RequestStub("not-an-ip");

        fixture.filter.filter(request.context());

        assertEquals(400, request.aborted.getStatus());
        assertEquals(0, fixture.registry.totalBucketCount());
        assertEquals(1L, fixture.stats.snapshot().get("forwardedResolutionFailed"));
    }

    @Test
    void missingOrShortTrustedForwardedChainsReturn400WithoutAllocatingBuckets() throws Exception {
        Map<String, String> values = active("ENFORCE", 10, 60, 10);
        values.put(RateLimitConfig.FORWARDED_ENABLED, "true");
        values.put(RateLimitConfig.FORWARDED_TRUSTED_PEERS, "192.0.2.44");
        values.put(RateLimitConfig.TRUSTED_PROXY_HOPS, "2");
        Fixture fixture = fixture(values);
        RequestStub missing = new RequestStub(null);
        RequestStub shortChain = new RequestStub("198.51.100.7");

        fixture.filter.filter(missing.context());
        fixture.filter.filter(shortChain.context());

        assertEquals(400, missing.aborted.getStatus());
        assertEquals(400, shortChain.aborted.getStatus());
        assertEquals(0, fixture.registry.totalBucketCount());
        assertEquals(2L, fixture.stats.snapshot().get("forwardedResolutionFailed"));
    }

    @Test
    void forgedHostsWithinOneForwardedNetworkCannotFillTheRegistry() throws Exception {
        Map<String, String> values = active("ENFORCE", 1, 60, 1);
        values.put(RateLimitConfig.OVERFLOW_SHARDS, "8");
        values.put(RateLimitConfig.FORWARDED_ENABLED, "true");
        values.put(RateLimitConfig.FORWARDED_TRUSTED_PEERS, "192.0.2.44");
        Fixture fixture = fixture(values);

        for (int index = 1; index <= 200; index++) {
            fixture.filter.filter(new RequestStub("198.51.100." + ((index % 254) + 1)).context());
        }
        RequestStub legitimateNewNetwork = new RequestStub("203.0.113.7");
        fixture.filter.filter(legitimateNewNetwork.context());

        assertEquals(1, fixture.registry.trackedBucketCount());
        assertEquals(2, fixture.registry.totalBucketCount());
        assertNull(legitimateNewNetwork.aborted);
    }

    private static Fixture fixture(Map<String, String> values) {
        RateLimitConfig config = new RateLimitConfig(values);
        FixedTime time = new FixedTime();
        RateLimitBucketRegistry registry = new RateLimitBucketRegistry(config, time, time);
        RateLimitStats stats = new RateLimitStats();
        QuantumRateLimitFilter filter = new QuantumRateLimitFilter(
                config,
                new RateLimitClientIdentity(),
                registry,
                stats,
                ignored -> "192.0.2.44");
        return new Fixture(filter, registry, stats, time);
    }

    private static Map<String, String> active(String mode, long limit, long refill, int cap) {
        Map<String, String> values = new HashMap<>();
        values.put(RateLimitConfig.MODE, mode);
        values.put(RateLimitConfig.REQUEST_LIMIT, Long.toString(limit));
        values.put(RateLimitConfig.REFILL_SECONDS, Long.toString(refill));
        values.put(RateLimitConfig.MAX_TRACKED_CLIENTS, Integer.toString(cap));
        values.put(RateLimitConfig.OVERFLOW_SHARDS, "1");
        values.put(RateLimitConfig.IDLE_SECONDS, "600");
        return values;
    }

    private record Fixture(
            QuantumRateLimitFilter filter,
            RateLimitBucketRegistry registry,
            RateLimitStats stats,
            FixedTime time) {
    }

    private static final class RequestStub {
        private final String forwardedFor;
        private Response aborted;

        private RequestStub(String forwardedFor) {
            this.forwardedFor = forwardedFor;
        }

        private ContainerRequestContext context() {
            return (ContainerRequestContext) Proxy.newProxyInstance(
                    ContainerRequestContext.class.getClassLoader(),
                    new Class<?>[]{ContainerRequestContext.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getHeaderString" -> QuantumRateLimitFilter.FORWARDED_FOR.equals(arguments[0])
                                ? forwardedFor
                                : null;
                        case "abortWith" -> {
                            aborted = (Response) arguments[0];
                            yield null;
                        }
                        default -> primitiveDefault(method.getReturnType());
                    });
        }

        private static Object primitiveDefault(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            return 0;
        }
    }

    private static final class FixedTime implements TimeMeter, LongSupplier {
        private final AtomicLong now = new AtomicLong();

        @Override
        public long currentTimeNanos() {
            return now.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advance(Duration duration) {
            now.addAndGet(duration.toNanos());
        }
    }
}
