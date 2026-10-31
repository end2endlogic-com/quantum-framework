package com.e2eq.ontology.metrics;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-provider metrics for the ontology calculated-relations subsystem.
 *
 * <p>This bean accumulates counters and timing data for each
 * {@code ComputedEdgeProvider}. It is intentionally dependency-free so it
 * can live in {@code quantum-ontology-core}; downstream modules can bridge
 * the snapshot to Micrometer/Prometheus if desired.</p>
 */
@ApplicationScoped
public class OntologyMetrics {

    private final Map<String, ProviderStats> byProvider = new ConcurrentHashMap<>();

    public void recordProviderInvocation(String providerId, long durationNanos, int targetsProduced) {
        ProviderStats s = stats(providerId);
        s.invocations.incrementAndGet();
        s.totalNanos.addAndGet(durationNanos);
        s.targetsProduced.addAndGet(targetsProduced);
        long max;
        do {
            max = s.maxNanos.get();
            if (durationNanos <= max) break;
        } while (!s.maxNanos.compareAndSet(max, durationNanos));
    }

    public void recordProviderError(String providerId) {
        stats(providerId).errors.incrementAndGet();
    }

    public void recordEdgesAdded(String providerId, int count) {
        if (count <= 0) return;
        stats(providerId).edgesAdded.addAndGet(count);
    }

    public void recordEdgesRemoved(String providerId, int count) {
        if (count <= 0) return;
        stats(providerId).edgesRemoved.addAndGet(count);
    }

    public void recordStalenessRisk(String providerId, String dependencyType) {
        stats(providerId).stalenessRiskByDependency
                .computeIfAbsent(dependencyType, k -> new AtomicLong())
                .incrementAndGet();
    }

    public void recordGuardTrip(String providerId, String guard) {
        stats(providerId).guardTrips
                .computeIfAbsent(guard, k -> new AtomicLong())
                .incrementAndGet();
    }

    public Map<String, Map<String, Object>> snapshot() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, ProviderStats> e : byProvider.entrySet()) {
            out.put(e.getKey(), e.getValue().snapshot());
        }
        return out;
    }

    public Map<String, Object> snapshotFor(String providerId) {
        ProviderStats s = byProvider.get(providerId);
        return s == null ? Map.of() : s.snapshot();
    }

    public void reset() {
        byProvider.clear();
    }

    private ProviderStats stats(String providerId) {
        return byProvider.computeIfAbsent(providerId, k -> new ProviderStats());
    }

    private static final class ProviderStats {
        final AtomicLong invocations = new AtomicLong();
        final AtomicLong totalNanos = new AtomicLong();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicLong targetsProduced = new AtomicLong();
        final AtomicLong edgesAdded = new AtomicLong();
        final AtomicLong edgesRemoved = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final Map<String, AtomicLong> guardTrips = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> stalenessRiskByDependency = new ConcurrentHashMap<>();

        Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            long inv = invocations.get();
            m.put("invocations", inv);
            m.put("totalNanos", totalNanos.get());
            m.put("avgNanos", inv == 0 ? 0L : totalNanos.get() / inv);
            m.put("maxNanos", maxNanos.get());
            m.put("targetsProduced", targetsProduced.get());
            m.put("edgesAdded", edgesAdded.get());
            m.put("edgesRemoved", edgesRemoved.get());
            m.put("errors", errors.get());
            m.put("guardTrips", flatten(guardTrips));
            m.put("stalenessRiskByDependency", flatten(stalenessRiskByDependency));
            return Collections.unmodifiableMap(m);
        }

        private static Map<String, Long> flatten(Map<String, AtomicLong> in) {
            Map<String, Long> out = new LinkedHashMap<>();
            for (Map.Entry<String, AtomicLong> e : in.entrySet()) {
                out.put(e.getKey(), e.getValue().get());
            }
            return out;
        }
    }
}
