package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple metrics collector for seed framework operations.
 * Tracks execution times, success/failure counts, and record counts.
 * 
 * In a production environment, this could be replaced with Micrometer metrics.
 */
@ApplicationScoped
public class SeedMetrics {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> recordCounts = new ConcurrentHashMap<>();

    /**
     * Records the execution of a seed pack application.
     *
     * @param seedPack the seed pack name
     * @param version  the seed pack version
     * @param realm    the realm identifier
     * @param success  whether the application was successful
     * @param durationMs execution duration in milliseconds
     * @param recordsApplied number of records applied
     */
    public void recordSeedApplication(String seedPack, String version, String realm,
                                     boolean success, long durationMs, int recordsApplied) {
        String key = String.format("%s@%s:%s", seedPack, version, realm);
        
        if (success) {
            incrementCounter("seed.applications.success", key);
            recordCounts.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(recordsApplied);
        } else {
            incrementCounter("seed.applications.failure", key);
        }
        
        timers.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(durationMs);
        
        Log.debugf("SeedMetrics: recorded %s application for %s (duration: %dms, records: %d)",
                success ? "successful" : "failed", key, durationMs, recordsApplied);
    }

    /**
     * Records a dataset application.
     *
     * @param seedPack the seed pack name
     * @param dataset  the dataset collection name
     * @param realm    the realm identifier
     * @param success  whether the application was successful
     * @param recordsApplied number of records applied
     */
    public void recordDatasetApplication(String seedPack, String dataset, String realm,
                                        boolean success, int recordsApplied) {
        String key = String.format("%s/%s:%s", seedPack, dataset, realm);
        incrementCounter(success ? "seed.datasets.success" : "seed.datasets.failure", key);
        if (success) {
            recordCounts.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(recordsApplied);
        }
    }

    /**
     * Records seed discovery operation.
     *
     * @param realm the realm identifier
     * @param packCount number of seed packs discovered
     * @param durationMs discovery duration in milliseconds
     */
    public void recordDiscovery(String realm, int packCount, long durationMs) {
        incrementCounter("seed.discoveries", realm);
        timers.computeIfAbsent("seed.discovery." + realm, k -> new AtomicLong(0)).addAndGet(durationMs);
        Log.debugf("SeedMetrics: recorded discovery for realm %s (%d packs, %dms)", realm, packCount, durationMs);
    }

    /**
     * Gets the total number of successful seed applications.
     *
     * @return total success count
     */
    public long getTotalSuccessCount() {
        return getCounterValue("seed.applications.success");
    }

    /**
     * Gets the total number of failed seed applications.
     *
     * @return total failure count
     */
    public long getTotalFailureCount() {
        return getCounterValue("seed.applications.failure");
    }

    /**
     * Gets the total number of records applied.
     *
     * @return total record count
     */
    public long getTotalRecordsApplied() {
        return recordCounts.values().stream()
                .mapToLong(AtomicLong::get)
                .sum();
    }

    /**
     * Gets metrics summary as a map.
     *
     * @return map of metric names to values
     */
    public java.util.Map<String, Object> getSummary() {
        java.util.Map<String, Object> summary = new java.util.HashMap<>();
        summary.put("totalSuccess", getTotalSuccessCount());
        summary.put("totalFailure", getTotalFailureCount());
        summary.put("totalRecordsApplied", getTotalRecordsApplied());
        summary.put("totalDiscoveries", getCounterValue("seed.discoveries"));
        return summary;
    }

    private void incrementCounter(String category, String key) {
        String fullKey = category + "." + key;
        counters.computeIfAbsent(fullKey, k -> new AtomicLong(0)).incrementAndGet();
    }

    private long getCounterValue(String category) {
        return counters.entrySet().stream()
                .filter(e -> e.getKey().startsWith(category + "."))
                .mapToLong(e -> e.getValue().get())
                .sum();
    }
}

