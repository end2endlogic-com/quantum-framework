package com.e2eq.framework.rest.usage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageBucketRegistryTest {

    @Test
    void consumesAtomicallyAndRefillsWithInjectedTime() {
        FixedTime time = new FixedTime();
        UsageBucketRegistry registry = new UsageBucketRegistry(10, Duration.ofMinutes(1).toNanos(), time);
        UsagePolicy policy = policy(2, Duration.ofSeconds(10));
        UsageRequest request = request("tenant-a", "subject-a", "listOrders");

        assertEquals(UsageBucketRegistry.Outcome.CONSUMED, registry.tryConsume(policy, request).outcome());
        assertEquals(UsageBucketRegistry.Outcome.CONSUMED, registry.tryConsume(policy, request).outcome());
        UsageBucketRegistry.AccessResult exhausted = registry.tryConsume(policy, request);
        assertEquals(UsageBucketRegistry.Outcome.EXHAUSTED, exhausted.outcome());
        assertEquals(Duration.ofSeconds(5).toNanos(), exhausted.retryNanos());

        time.advance(Duration.ofSeconds(5));
        assertEquals(UsageBucketRegistry.Outcome.CONSUMED, registry.tryConsume(policy, request).outcome());
    }

    @Test
    void capacityOverflowIsObservableButCannotDenyAnotherTenant() {
        FixedTime time = new FixedTime();
        UsageBucketRegistry registry = new UsageBucketRegistry(1, Duration.ofSeconds(5).toNanos(), time);
        UsagePolicy policy = policy(10, Duration.ofMinutes(1));

        assertEquals(UsageBucketRegistry.Outcome.CONSUMED,
                registry.tryConsume(policy, request("tenant-a", "subject-a", "read")).outcome());
        assertEquals(UsageBucketRegistry.Outcome.CAPACITY_BYPASSED,
                registry.tryConsume(policy, request("tenant-b", "subject-b", "read")).outcome());
        assertEquals(1, registry.trackedBucketCount());

        time.advance(Duration.ofSeconds(5));
        assertEquals(UsageBucketRegistry.Outcome.CONSUMED,
                registry.tryConsume(policy, request("tenant-b", "subject-b", "read")).outcome());
        assertEquals(1, registry.trackedBucketCount());
    }

    @Test
    void oneTenantExhaustionNeverConsumesAnotherTenantsAllowance() {
        FixedTime time = new FixedTime();
        UsageBucketRegistry registry = new UsageBucketRegistry(10, Duration.ofMinutes(1).toNanos(), time);
        UsagePolicy policy = policy(1, Duration.ofMinutes(1));
        UsageRequest tenantA = request("tenant-a", "subject-a", "read");
        UsageRequest tenantB = request("tenant-b", "subject-b", "read");

        assertEquals(UsageBucketRegistry.Outcome.CONSUMED, registry.tryConsume(policy, tenantA).outcome());
        assertEquals(UsageBucketRegistry.Outcome.EXHAUSTED, registry.tryConsume(policy, tenantA).outcome());
        assertEquals(UsageBucketRegistry.Outcome.CONSUMED, registry.tryConsume(policy, tenantB).outcome());
        assertEquals(2, registry.trackedBucketCount());
    }

    @Test
    void concurrentConsumptionCannotExceedCapacity() throws Exception {
        FixedTime time = new FixedTime();
        UsageBucketRegistry registry = new UsageBucketRegistry(10, Duration.ofMinutes(1).toNanos(), time);
        UsagePolicy policy = policy(25, Duration.ofMinutes(1));
        UsageRequest request = request("tenant-a", "subject-a", "write");
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger consumed = new AtomicInteger();
        try {
            for (int index = 0; index < 100; index++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        if (registry.tryConsume(policy, request).outcome()
                                == UsageBucketRegistry.Outcome.CONSUMED) {
                            consumed.incrementAndGet();
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(25, consumed.get());
    }

    private static UsagePolicy policy(long limit, Duration period) {
        return new UsagePolicy("test", "1", limit, period, Set.of(UsagePolicy.ALL_ENDPOINTS));
    }

    private static UsageRequest request(String tenant, String subject, String operation) {
        return new UsageRequest(
                new UsageEndpointIdentity("sales", "order", operation),
                new UsagePrincipalIdentity(tenant, subject),
                "GET",
                -1);
    }

    static final class FixedTime implements LongSupplier {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long getAsLong() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
