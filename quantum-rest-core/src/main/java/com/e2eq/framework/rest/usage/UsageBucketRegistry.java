package com.e2eq.framework.rest.usage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Atomic, per-JVM token buckets with a strict key bound and opportunistic idle eviction. */
@ApplicationScoped
public class UsageBucketRegistry {

    private final int maxTrackedKeys;
    private final long idleNanos;
    private final long sweepIntervalNanos;
    private final LongSupplier ticker;
    private final Map<BucketKey, BucketEntry> entries = new ConcurrentHashMap<>();
    private final ReentrantLock creationLock = new ReentrantLock();
    private final AtomicLong nextSweepNanos;

    @Inject
    public UsageBucketRegistry(UsageGovernanceConfig config) {
        this(config.maxTrackedKeys(), config.idleTimeout().toNanos(), System::nanoTime);
    }

    UsageBucketRegistry(
            int maxTrackedKeys,
            long idleNanos,
            LongSupplier ticker) {
        if (maxTrackedKeys <= 0) {
            throw new IllegalArgumentException("maxTrackedKeys must be greater than zero");
        }
        if (idleNanos <= 0) {
            throw new IllegalArgumentException("idleNanos must be greater than zero");
        }
        this.maxTrackedKeys = maxTrackedKeys;
        this.idleNanos = idleNanos;
        this.sweepIntervalNanos = Math.min(idleNanos, 60_000_000_000L);
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.nextSweepNanos = new AtomicLong(safeAdd(ticker.getAsLong(), sweepIntervalNanos));
    }

    AccessResult tryConsume(UsagePolicy policy, UsageRequest request) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(request, "request");
        long now = ticker.getAsLong();
        BucketKey key = bucketKey(policy, request);
        BucketEntry entry = touch(key, now);

        if (entry == null) {
            creationLock.lock();
            try {
                entry = touch(key, now);
                if (entry == null) {
                    sweepIfDue(now);
                    if (entries.size() >= maxTrackedKeys) {
                        // Memory protection must not become a cross-tenant denial primitive.
                        // The request remains observable but is not admitted by an untracked bucket.
                        return AccessResult.capacityBypassed();
                    }
                    entry = new BucketEntry(newBucket(policy), now);
                    entries.put(key, entry);
                }
            } finally {
                creationLock.unlock();
            }
        }

        sweepIfDue(now);
        return entry.bucket.tryConsume(now);
    }

    private BucketEntry touch(BucketKey key, long now) {
        return entries.computeIfPresent(key, (ignored, current) -> {
            current.lastAccessNanos = now;
            return current;
        });
    }

    private AtomicTokenBucket newBucket(UsagePolicy policy) {
        return new AtomicTokenBucket(policy.requestLimit(), policy.refillPeriod().toNanos(), ticker.getAsLong());
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
        for (BucketKey key : entries.keySet()) {
            boolean[] removedCurrent = {false};
            entries.computeIfPresent(key, (ignored, current) -> {
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
        return removed;
    }

    int trackedBucketCount() {
        return entries.size();
    }

    private boolean isIdle(BucketEntry entry, long now) {
        return now >= entry.lastAccessNanos && now - entry.lastAccessNanos >= idleNanos;
    }

    private static BucketKey bucketKey(UsagePolicy policy, UsageRequest request) {
        return new BucketKey(
                policy.id(),
                policy.version(),
                policy.requestLimit(),
                policy.refillPeriod().toNanos(),
                request.endpoint().canonicalName(),
                request.principal().tenantId(),
                request.principal().subjectId());
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    record AccessResult(Outcome outcome, long remainingTokens, long retryNanos) {
        static AccessResult consumed(long remainingTokens) {
            return new AccessResult(Outcome.CONSUMED, remainingTokens, 0);
        }

        static AccessResult exhausted(long retryNanos) {
            return new AccessResult(Outcome.EXHAUSTED, 0, Math.max(1, retryNanos));
        }

        static AccessResult capacityBypassed() {
            return new AccessResult(Outcome.CAPACITY_BYPASSED, -1, 0);
        }
    }

    enum Outcome {
        CONSUMED,
        EXHAUSTED,
        CAPACITY_BYPASSED
    }

    private record BucketKey(
            String policyId,
            String policyVersion,
            long requestLimit,
            long refillNanos,
            String endpointId,
            String tenantId,
            String subjectId) {
    }

    private static final class BucketEntry {
        private final AtomicTokenBucket bucket;
        private volatile long lastAccessNanos;

        private BucketEntry(AtomicTokenBucket bucket, long lastAccessNanos) {
            this.bucket = bucket;
            this.lastAccessNanos = lastAccessNanos;
        }
    }

    /**
     * Lock-free continuous-refill token bucket. State is replaced atomically so
     * concurrent callers cannot consume the same token.
     */
    private static final class AtomicTokenBucket {
        private final long capacity;
        private final long refillNanos;
        private final AtomicReference<TokenState> state;

        private AtomicTokenBucket(long capacity, long refillNanos, long startedNanos) {
            if (capacity <= 0 || refillNanos <= 0) {
                throw new IllegalArgumentException("capacity and refillNanos must be greater than zero");
            }
            this.capacity = capacity;
            this.refillNanos = refillNanos;
            this.state = new AtomicReference<>(new TokenState(capacity, startedNanos));
        }

        private AccessResult tryConsume(long now) {
            while (true) {
                TokenState current = state.get();
                long effectiveNow = Math.max(now, current.lastRefillNanos());
                long elapsed = effectiveNow - current.lastRefillNanos();
                double refilled = Math.min(
                        (double) capacity,
                        current.availableTokens() + ((double) elapsed * (double) capacity) / refillNanos);

                if (refilled >= 1.0d) {
                    double remaining = refilled - 1.0d;
                    TokenState next = new TokenState(remaining, effectiveNow);
                    if (state.compareAndSet(current, next)) {
                        return AccessResult.consumed((long) Math.floor(remaining));
                    }
                    continue;
                }

                long retryNanos = Math.max(
                        1L,
                        (long) Math.ceil(((1.0d - refilled) * (double) refillNanos) / (double) capacity));
                TokenState next = new TokenState(refilled, effectiveNow);
                if (state.compareAndSet(current, next)) {
                    return AccessResult.exhausted(retryNanos);
                }
            }
        }
    }

    private record TokenState(double availableTokens, long lastRefillNanos) {
    }
}
