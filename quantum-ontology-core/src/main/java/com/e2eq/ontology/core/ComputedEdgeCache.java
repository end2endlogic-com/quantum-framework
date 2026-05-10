package com.e2eq.ontology.core;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-process TTL'd cache for {@link MaterializationMode#LAZY} computed edges.
 *
 * <p>Keyed by ({@code providerId}, {@code realm}, {@code tenant}, {@code sourceId}).
 * Values are immutable {@code List<Reasoner.Edge>}.</p>
 *
 * <p>Implementation is intentionally a simple ConcurrentHashMap with
 * lazy-eviction on read; we don't pull in Caffeine here to keep
 * {@code quantum-ontology-core} dependency-free. The hot path is amortized
 * O(1) and entries are bounded by the live key set + TTL.</p>
 */
@ApplicationScoped
public class ComputedEdgeCache {

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public Optional<List<Reasoner.Edge>> get(Key key) {
        Entry e = entries.get(key);
        if (e == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (e.isExpired()) {
            entries.remove(key, e);
            evictions.incrementAndGet();
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(e.edges);
    }

    public void put(Key key, List<Reasoner.Edge> edges, long ttlSeconds) {
        long expiresAtMillis = ttlSeconds > 0
                ? System.currentTimeMillis() + ttlSeconds * 1000L
                : Long.MAX_VALUE;
        entries.put(key, new Entry(List.copyOf(edges), expiresAtMillis));
    }

    public void invalidate(Key key) {
        if (entries.remove(key) != null) evictions.incrementAndGet();
    }

    public int invalidateProvider(String providerId) {
        int n = 0;
        for (Iterator<Key> it = entries.keySet().iterator(); it.hasNext(); ) {
            Key k = it.next();
            if (Objects.equals(k.providerId, providerId)) {
                it.remove();
                n++;
            }
        }
        evictions.addAndGet(n);
        return n;
    }

    public void clear() {
        evictions.addAndGet(entries.size());
        entries.clear();
    }

    public Map<String, Long> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("size", (long) entries.size());
        m.put("hits", hits.get());
        m.put("misses", misses.get());
        m.put("evictions", evictions.get());
        return m;
    }

    public record Key(String providerId, String realm, String tenant, String sourceId) {}

    private static final class Entry {
        final List<Reasoner.Edge> edges;
        final long expiresAtMillis;
        Entry(List<Reasoner.Edge> edges, long expiresAtMillis) {
            this.edges = edges; this.expiresAtMillis = expiresAtMillis;
        }
        boolean isExpired() { return System.currentTimeMillis() >= expiresAtMillis; }
    }
}
