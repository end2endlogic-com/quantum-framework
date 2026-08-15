package com.e2eq.framework.rest.ratelimit;

import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitBucketRegistryTest {

    @Test
    void enforcesCapacityAndRefillsWithoutSleeping() {
        FixedTime time = new FixedTime();
        RateLimitBucketRegistry registry = registry(time, config(2, 10, 10, 60));

        assertTrue(registry.tryConsume("client").probe().isConsumed());
        assertTrue(registry.tryConsume("client").probe().isConsumed());
        assertFalse(registry.tryConsume("client").probe().isConsumed());

        time.advance(Duration.ofSeconds(5));
        assertTrue(registry.tryConsume("client").probe().isConsumed());
    }

    @Test
    void distinctClientsCannotGrowTheRegistryPastTheConfiguredCap() {
        FixedTime time = new FixedTime();
        int cap = 10;
        RateLimitBucketRegistry registry = registry(time, config(100, 60, cap, 600));

        for (int index = 0; index < cap * 10; index++) {
            registry.tryConsume("client-" + index);
        }

        assertEquals(cap, registry.trackedBucketCount());
        assertEquals(cap + 1, registry.totalBucketCount(), "one shared overflow bucket is the only extra bucket");
    }

    @Test
    void idleEntriesAreEvictedBeforeAllocatingNewClients() {
        FixedTime time = new FixedTime();
        RateLimitBucketRegistry registry = registry(time, config(10, 60, 2, 5));
        registry.tryConsume("first");
        registry.tryConsume("second");

        time.advance(Duration.ofSeconds(5));
        registry.tryConsume("third");

        assertEquals(1, registry.trackedBucketCount());
        assertEquals(1, registry.totalBucketCount());
    }

    @Test
    void overflowClientsShareOneRateLimit() {
        FixedTime time = new FixedTime();
        RateLimitBucketRegistry registry = registry(time, config(1, 60, 1, 600));
        assertTrue(registry.tryConsume("tracked").probe().isConsumed());

        RateLimitBucketRegistry.AccessResult firstOverflow = registry.tryConsume("overflow-a");
        RateLimitBucketRegistry.AccessResult secondOverflow = registry.tryConsume("overflow-b");

        assertTrue(firstOverflow.overflow());
        assertTrue(firstOverflow.probe().isConsumed());
        assertTrue(secondOverflow.overflow());
        assertFalse(secondOverflow.probe().isConsumed());
    }

    @Test
    void activeOverflowTrafficRefreshesTheSharedBucketsIdleDeadline() {
        FixedTime time = new FixedTime();
        RateLimitBucketRegistry registry = registry(time, config(1, 60, 1, 5));
        registry.tryConsume("tracked");
        assertTrue(registry.tryConsume("overflow-a").probe().isConsumed());

        time.advance(Duration.ofSeconds(4));
        registry.tryConsume("tracked");
        assertFalse(registry.tryConsume("overflow-b").probe().isConsumed());
        time.advance(Duration.ofSeconds(4));

        assertFalse(
                registry.tryConsume("overflow-c").probe().isConsumed(),
                "continuous overflow use must not replace the shared bucket at its original idle deadline");
    }

    @Test
    void concurrentMissesStillRespectTheStrictCap() throws Exception {
        FixedTime time = new FixedTime();
        int cap = 10;
        RateLimitBucketRegistry registry = registry(time, config(1_000, 60, cap, 600));
        var tasks = IntStream.range(0, 200)
                .<Callable<Void>>mapToObj(index -> () -> {
                    registry.tryConsume("concurrent-client-" + index);
                    return null;
                })
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            executor.invokeAll(tasks);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(cap, registry.trackedBucketCount());
        assertEquals(cap + 1, registry.totalBucketCount());
    }

    private static RateLimitBucketRegistry registry(FixedTime time, RateLimitConfig config) {
        return new RateLimitBucketRegistry(config, time, time);
    }

    private static RateLimitConfig config(long limit, long refillSeconds, int cap, long idleSeconds) {
        Map<String, String> values = new HashMap<>();
        values.put(RateLimitConfig.MODE, "ENFORCE");
        values.put(RateLimitConfig.REQUEST_LIMIT, Long.toString(limit));
        values.put(RateLimitConfig.REFILL_SECONDS, Long.toString(refillSeconds));
        values.put(RateLimitConfig.MAX_TRACKED_CLIENTS, Integer.toString(cap));
        values.put(RateLimitConfig.OVERFLOW_SHARDS, "1");
        values.put(RateLimitConfig.IDLE_SECONDS, Long.toString(idleSeconds));
        return new RateLimitConfig(values);
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
