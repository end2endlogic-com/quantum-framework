package com.e2eq.framework.rest.ratelimit;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dependency-free counters suitable for bridging to an application's metrics facility. */
@ApplicationScoped
public class RateLimitStats {

    private final LongAdder allowed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder wouldBeRejected = new LongAdder();
    private final LongAdder overflowAssignments = new LongAdder();
    private final LongAdder forwardedResolutionFailed = new LongAdder();
    private final AtomicBoolean forwardedFailureLogged = new AtomicBoolean();

    void recordAllowed() {
        allowed.increment();
    }

    void recordRejected() {
        rejected.increment();
    }

    void recordWouldBeRejected() {
        wouldBeRejected.increment();
    }

    void recordOverflowAssignment() {
        overflowAssignments.increment();
    }

    boolean recordForwardedResolutionFailed() {
        forwardedResolutionFailed.increment();
        return forwardedFailureLogged.compareAndSet(false, true);
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("allowed", allowed.sum());
        snapshot.put("rejected", rejected.sum());
        snapshot.put("wouldBeRejected", wouldBeRejected.sum());
        snapshot.put("overflowAssignments", overflowAssignments.sum());
        snapshot.put("forwardedResolutionFailed", forwardedResolutionFailed.sum());
        return Map.copyOf(snapshot);
    }

    public void reset() {
        allowed.reset();
        rejected.reset();
        wouldBeRejected.reset();
        overflowAssignments.reset();
        forwardedResolutionFailed.reset();
        forwardedFailureLogged.set(false);
    }
}
