package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.semver4j.Semver;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Centralized service for seed pack discovery and version resolution.
 * Eliminates code duplication across SeedStartupRunner, SeedAdminResource, etc.
 */
@ApplicationScoped
public class SeedDiscoveryService {

    @Inject
    Instance<SeedSource> seedSources;

    @Inject
    SeedPathResolver seedPathResolver;

    @Inject
    SeedMetrics seedMetrics;

    @ConfigProperty(name = "quantum.seed.discovery.cache-ttl-seconds", defaultValue = "60")
    long cacheTtlSeconds;

    @ConfigProperty(name = "quantum.seed.discovery.parallel", defaultValue = "true")
    boolean parallelDiscovery;

    // Simple in-memory cache with TTL
    private static class CacheEntry {
        final List<SeedPackDescriptor> descriptors;
        final long timestamp;

        CacheEntry(List<SeedPackDescriptor> descriptors) {
            this.descriptors = descriptors;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }

    private final Map<String, CacheEntry> discoveryCache = new HashMap<>();

    /**
     * Discovers all seed packs from all available seed sources for the given context.
     * Results are cached for the configured TTL to improve performance.
     *
     * @param context the seed context
     * @return list of all discovered seed pack descriptors
     * @throws IOException if discovery fails
     */
    public List<SeedPackDescriptor> discoverSeedPacks(SeedContext context) throws IOException {
        String cacheKey = context.getRealm();
        CacheEntry cached = discoveryCache.get(cacheKey);
        long ttlMs = cacheTtlSeconds * 1000;

        if (cached != null && !cached.isExpired(ttlMs)) {
            Log.debugf("SeedDiscoveryService: returning cached seed packs for realm %s", context.getRealm());
            return cached.descriptors;
        }

        long startTime = System.currentTimeMillis();
        List<SeedPackDescriptor> descriptors = new ArrayList<>();

        // Set MDC for structured logging
        org.slf4j.MDC.put("realm", context.getRealm());
        org.slf4j.MDC.put("operation", "discoverSeedPacks");

        try {
            // Discover from all CDI-managed seed sources
            if (parallelDiscovery && seedSources.stream().count() > 1) {
                // Parallel discovery for multiple sources
                descriptors = discoverParallel(context);
            } else {
                // Sequential discovery
                for (SeedSource source : seedSources) {
                    try {
                        List<SeedPackDescriptor> sourceDescriptors = source.loadSeedPacks(context);
                        Log.debugf("SeedDiscoveryService: source %s found %d seed pack(s) for realm %s",
                                source.getId(), sourceDescriptors.size(), context.getRealm());
                        descriptors.addAll(sourceDescriptors);
                    } catch (IOException e) {
                        Log.warnf("SeedDiscoveryService: failed to load seed packs from source %s: %s",
                                source.getId(), e.getMessage());
                        // Continue with other sources
                    }
                }
            }

            // Cache the results
            discoveryCache.put(cacheKey, new CacheEntry(descriptors));
            
            long duration = System.currentTimeMillis() - startTime;
            seedMetrics.recordDiscovery(context.getRealm(), descriptors.size(), duration);
            
            Log.infof("SeedDiscoveryService: discovered %d total seed pack(s) for realm %s in %dms (cached)",
                    descriptors.size(), context.getRealm(), duration);

            return descriptors;
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    /**
     * Discovers seed packs from all sources in parallel for better performance.
     *
     * @param context the seed context
     * @return list of all discovered seed pack descriptors
     */
    private List<SeedPackDescriptor> discoverParallel(SeedContext context) {
        List<SeedSource> sourcesList = new ArrayList<>();
        seedSources.forEach(sourcesList::add);

        return sourcesList.parallelStream()
                .flatMap(source -> {
                    try {
                        List<SeedPackDescriptor> sourceDescriptors = source.loadSeedPacks(context);
                        Log.debugf("SeedDiscoveryService: source %s found %d seed pack(s) for realm %s",
                                source.getId(), sourceDescriptors.size(), context.getRealm());
                        return sourceDescriptors.stream();
                    } catch (IOException e) {
                        Log.warnf("SeedDiscoveryService: failed to load seed packs from source %s: %s",
                                source.getId(), e.getMessage());
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Filters seed packs by scope applicability.
     *
     * @param descriptors the seed pack descriptors to filter
     * @param context the seed context
     * @return filtered list of applicable seed packs
     */
    public List<SeedPackDescriptor> filterByScope(List<SeedPackDescriptor> descriptors, SeedContext context) {
        List<SeedPackDescriptor> filtered = new ArrayList<>(descriptors);
        filtered.removeIf(d -> !ScopeMatcher.isApplicable(d, context));
        return filtered;
    }

    /**
     * Filters seed packs by a CSV list of allowed seed pack names.
     *
     * @param descriptors the seed pack descriptors to filter
     * @param filterCsv comma-separated list of seed pack names (null/empty = no filter)
     * @return filtered list of seed packs
     */
    public List<SeedPackDescriptor> filterByNames(List<SeedPackDescriptor> descriptors, String filterCsv) {
        if (filterCsv == null || filterCsv.isBlank()) {
            return descriptors;
        }

        Set<String> allowed = parseFilterCsv(filterCsv);
        if (allowed.isEmpty()) {
            return descriptors;
        }

        List<SeedPackDescriptor> filtered = new ArrayList<>(descriptors);
        filtered.removeIf(d -> !allowed.contains(d.getManifest().getSeedPack()));
        return filtered;
    }

    /**
     * Finds the latest version of each seed pack from the given descriptors.
     * Uses Semver for proper version comparison.
     *
     * @param descriptors the seed pack descriptors
     * @return map of seed pack name to latest version descriptor
     */
    public Map<String, SeedPackDescriptor> findLatestVersions(List<SeedPackDescriptor> descriptors) {
        Map<String, SeedPackDescriptor> latestByName = new LinkedHashMap<>();
        for (SeedPackDescriptor d : descriptors) {
            String name = d.getManifest().getSeedPack();
            latestByName.merge(name, d, (a, b) -> {
                Semver versionA = Semver.parse(a.getManifest().getVersion());
                Semver versionB = Semver.parse(b.getManifest().getVersion());
                if (versionA == null || versionB == null) {
                    // Fallback to string comparison if Semver parsing fails
                    return a.getManifest().getVersion().compareTo(b.getManifest().getVersion()) >= 0 ? a : b;
                }
                return versionA.isGreaterThanOrEqualTo(versionB) ? a : b;
            });
        }
        return latestByName;
    }

    /**
     * Calculates the SHA-256 checksum of a dataset file.
     *
     * @param descriptor the seed pack descriptor
     * @param dataset the dataset
     * @return the hex-encoded checksum
     */
    public String calculateChecksum(SeedPackDescriptor descriptor, SeedPackManifest.Dataset dataset) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = openDataset(descriptor, dataset.getFile())) {
                byte[] bytes = in.readAllBytes();
                return java.util.HexFormat.of().formatHex(digest.digest(bytes));
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.warnf("SeedDiscoveryService: failed to calculate checksum for %s/%s: %s",
                    descriptor.getManifest().getSeedPack(), dataset.getFile(), e.getMessage());
            return "";
        }
    }

    /**
     * Opens a dataset file, handling classpath:, URI, and relative path references.
     *
     * @param descriptor the seed pack descriptor
     * @param pathOrUri the file path or URI
     * @return an input stream for the dataset
     * @throws IOException if the dataset cannot be opened
     */
    private InputStream openDataset(SeedPackDescriptor descriptor, String pathOrUri) throws IOException {
        if (isClasspath(pathOrUri)) {
            String cp = pathOrUri.substring("classpath:".length());
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            InputStream in = cl.getResourceAsStream(cp);
            if (in == null) {
                throw new IOException("Classpath dataset not found: " + cp);
            }
            return in;
        }
        if (isUri(pathOrUri)) {
            java.net.URI uri = java.net.URI.create(pathOrUri);
            String scheme = uri.getScheme();
            for (SeedSource source : seedSources) {
                if (source instanceof SchemeAware sa && sa.supportsScheme(scheme)) {
                    return sa.openUri(uri, descriptor);
                }
            }
            throw new IOException("No SeedSource found for URI scheme '" + scheme + "'");
        }
        // Backward compatible relative path resolution via owning source
        return descriptor.getSource().openDataset(descriptor, pathOrUri);
    }

    private static boolean isClasspath(String value) {
        return value != null && value.startsWith("classpath:");
    }

    private static boolean isUri(String value) {
        if (value == null) return false;
        int i = value.indexOf(":");
        return i > 1 && value.contains("://");
    }

    /**
     * Discovers, filters, and resolves latest versions of seed packs in one call.
     * This is a convenience method that combines multiple discovery steps.
     *
     * @param context the seed context
     * @param filterCsv optional CSV filter for seed pack names
     * @return map of seed pack name to latest version descriptor
     * @throws IOException if discovery fails
     */
    public Map<String, SeedPackDescriptor> discoverLatestApplicable(SeedContext context, String filterCsv) throws IOException {
        List<SeedPackDescriptor> descriptors = discoverSeedPacks(context);
        descriptors = filterByScope(descriptors, context);
        descriptors = filterByNames(descriptors, filterCsv);
        return findLatestVersions(descriptors);
    }

    /**
     * Clears the discovery cache for a specific realm or all realms.
     *
     * @param realm the realm to clear (null = clear all)
     */
    public void clearCache(String realm) {
        if (realm == null) {
            discoveryCache.clear();
            Log.debugf("SeedDiscoveryService: cleared all discovery cache");
        } else {
            discoveryCache.remove(realm);
            Log.debugf("SeedDiscoveryService: cleared discovery cache for realm %s", realm);
        }
    }

    /**
     * Parses a CSV filter string into a set of allowed seed pack names.
     *
     * @param csv the comma-separated list
     * @return set of allowed names (empty if csv is null/blank)
     */
    private Set<String> parseFilterCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                set.add(name);
            }
        }
        return set;
    }
}

