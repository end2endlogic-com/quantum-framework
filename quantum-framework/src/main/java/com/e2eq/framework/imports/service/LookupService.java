package com.e2eq.framework.imports.service;

import com.e2eq.framework.model.persistent.imports.LookupConfig;

import java.util.Optional;

/**
 * Service for performing cross-collection lookups during CSV import.
 * Implementations handle caching and query execution.
 */
public interface LookupService {

    /**
     * Perform a lookup to resolve a value from another collection.
     *
     * @param csvValue the value from the CSV to look up
     * @param config the lookup configuration
     * @param realmId the realm ID for the lookup query
     * @return the resolved value, or empty if not found
     */
    Optional<Object> lookup(String csvValue, LookupConfig config, String realmId);

    /**
     * Clear any cached lookups.
     * Should be called at the start of an import session.
     */
    void clearCache();

    /**
     * Clear cached lookups for a specific collection.
     *
     * @param collection the collection name
     */
    void clearCache(String collection);

    /**
     * Get cache statistics for monitoring.
     *
     * @return cache statistics
     */
    LookupCacheStats getCacheStats();

    /**
     * Cache statistics for monitoring lookup performance.
     */
    class LookupCacheStats {
        private long hits;
        private long misses;
        private long evictions;
        private int cacheSize;

        public LookupCacheStats(long hits, long misses, long evictions, int cacheSize) {
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.cacheSize = cacheSize;
        }

        public long getHits() {
            return hits;
        }

        public long getMisses() {
            return misses;
        }

        public long getEvictions() {
            return evictions;
        }

        public int getCacheSize() {
            return cacheSize;
        }

        public double getHitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
    }
}
