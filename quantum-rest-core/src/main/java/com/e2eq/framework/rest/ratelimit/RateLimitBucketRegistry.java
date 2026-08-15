package com.e2eq.framework.rest.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Per-JVM, bounded registry of idle-evicted token buckets. */
@ApplicationScoped
public class RateLimitBucketRegistry {

    private final RateLimitConfig config;
    private final TimeMeter bucketTimeMeter;
    private final LongSupplier ticker;
    private final long idleNanos;
    private final long sweepIntervalNanos;
    private final Map<String, BucketEntry> entries = new ConcurrentHashMap<>();
    private final ReentrantLock creationLock = new ReentrantLock();
    private final AtomicLong nextSweepNanos;
    private final AtomicReferenceArray<BucketEntry> overflowEntries;

    @Inject
    public RateLimitBucketRegistry(RateLimitConfig config) {
        this(config, TimeMeter.SYSTEM_NANOTIME, System::nanoTime);
    }

    RateLimitBucketRegistry(RateLimitConfig config, TimeMeter bucketTimeMeter, LongSupplier ticker) {
        this.config = Objects.requireNonNull(config, "config");
        this.bucketTimeMeter = Objects.requireNonNull(bucketTimeMeter, "bucketTimeMeter");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.idleNanos = config.idleTimeout().toNanos();
        this.sweepIntervalNanos = Math.min(idleNanos, 60_000_000_000L);
        this.nextSweepNanos = new AtomicLong(safeAdd(ticker.getAsLong(), sweepIntervalNanos));
        this.overflowEntries = new AtomicReferenceArray<>(config.overflowShards());
    }

    AccessResult tryConsume(String clientKey) {
        Objects.requireNonNull(clientKey, "clientKey");
        long now = ticker.getAsLong();
        BucketEntry entry = touchTrackedEntry(clientKey, now);
        boolean overflow = false;

        if (entry == null) {
            creationLock.lock();
            try {
                entry = touchTrackedEntry(clientKey, now);
                if (entry == null) {
                    sweepIfDue(now);
                    if (entries.size() >= config.maxTrackedClients()) {
                        entry = overflowEntry(clientKey, now);
                        overflow = true;
                    } else {
                        entry = new BucketEntry(newBucket(), now);
                        entries.put(clientKey, entry);
                    }
                }
            } finally {
                creationLock.unlock();
            }
        }

        sweepIfDue(now);
        return new AccessResult(entry.bucket.tryConsumeAndReturnRemaining(1), overflow);
    }

    private BucketEntry touchTrackedEntry(String clientKey, long now) {
        return entries.computeIfPresent(clientKey, (ignored, current) -> {
            current.lastAccessNanos = now;
            return current;
        });
    }

    private BucketEntry overflowEntry(String clientKey, long now) {
        int shard = Math.floorMod(clientKey.hashCode(), overflowEntries.length());
        while (true) {
            BucketEntry current = overflowEntries.get(shard);
            if (current != null && !isIdle(current, now)) {
                current.lastAccessNanos = now;
                return current;
            }
            BucketEntry replacement = new BucketEntry(newBucket(), now);
            if (overflowEntries.compareAndSet(shard, current, replacement)) {
                return replacement;
            }
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(config.requestLimit(), config.refillPeriod()))
                .withCustomTimePrecision(bucketTimeMeter)
                .build();
    }

    private void sweepIfDue(long now) {
        long scheduled = nextSweepNanos.get();
        if (now < scheduled || !nextSweepNanos.compareAndSet(scheduled, safeAdd(now, sweepIntervalNanos))) {
            return;
        }
        evictIdle(now);
    }

    int evictIdle(long now) {
        int removed = 0;
        for (String clientKey : entries.keySet()) {
            boolean[] removedCurrent = {false};
            entries.computeIfPresent(clientKey, (ignored, current) -> {
                if (isIdle(current, now)) {
                    removedCurrent[0] = true;
                    return null;
                }
                return current;
            });
            if (removedCurrent[0]) {
                removed++;
            }
        }
        for (int shard = 0; shard < overflowEntries.length(); shard++) {
            BucketEntry current = overflowEntries.get(shard);
            if (current != null && isIdle(current, now)) {
                overflowEntries.compareAndSet(shard, current, null);
            }
        }
        return removed;
    }

    private boolean isIdle(BucketEntry entry, long now) {
        return now >= entry.lastAccessNanos && now - entry.lastAccessNanos >= idleNanos;
    }

    int trackedBucketCount() {
        return entries.size();
    }

    int totalBucketCount() {
        int overflowCount = 0;
        for (int shard = 0; shard < overflowEntries.length(); shard++) {
            if (overflowEntries.get(shard) != null) {
                overflowCount++;
            }
        }
        return entries.size() + overflowCount;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    record AccessResult(ConsumptionProbe probe, boolean overflow) {
    }

    private static final class BucketEntry {
        private final Bucket bucket;
        private volatile long lastAccessNanos;

        private BucketEntry(Bucket bucket, long lastAccessNanos) {
            this.bucket = bucket;
            this.lastAccessNanos = lastAccessNanos;
        }
    }
}
