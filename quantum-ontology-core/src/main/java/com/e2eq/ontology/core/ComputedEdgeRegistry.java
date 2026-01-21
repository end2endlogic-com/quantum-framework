package com.e2eq.ontology.core;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry that tracks computed edge providers and their dependencies for incremental updates.
 *
 * <p>When a dependency entity changes (e.g., a Territory's LocationList is modified),
 * this registry can identify:</p>
 * <ul>
 *   <li>Which providers need to recompute edges</li>
 *   <li>Which source entities are affected</li>
 * </ul>
 *
 * <p>This enables efficient incremental updates rather than full recomputation.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @Inject ComputedEdgeRegistry registry;
 *
 * // When a Territory is modified:
 * Map<ComputedEdgeProvider<?>, Set<String>> affected =
 *     registry.findAffectedSources(realmId, domain, Territory.class, territoryId);
 *
 * // Recompute edges for affected sources
 * for (var entry : affected.entrySet()) {
 *     ComputedEdgeProvider<?> provider = entry.getKey();
 *     for (String sourceId : entry.getValue()) {
 *         // Load source entity and recompute edges
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations should be registered as CDI beans. The framework will
 * automatically discover and register ComputedEdgeProvider beans.</p>
 */
@ApplicationScoped
public class ComputedEdgeRegistry {

    /** Maps dependency type -> providers that depend on it */
    private final Map<Class<?>, List<ComputedEdgeProvider<?>>> dependencyToProviders = new ConcurrentHashMap<>();

    /** All registered providers */
    private final List<ComputedEdgeProvider<?>> allProviders = new CopyOnWriteArrayList<>();

    /** Maps provider ID -> provider for lookup */
    private final Map<String, ComputedEdgeProvider<?>> providerById = new ConcurrentHashMap<>();

    /**
     * Register a provider with its dependencies.
     *
     * <p>This is typically called during application startup when CDI discovers
     * ComputedEdgeProvider beans.</p>
     *
     * @param provider the provider to register
     */
    public void register(ComputedEdgeProvider<?> provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider cannot be null");
        }

        String providerId = provider.getProviderId();
        if (providerById.containsKey(providerId)) {
            // Already registered, skip
            return;
        }

        allProviders.add(provider);
        providerById.put(providerId, provider);

        // Register dependency mappings
        for (Class<?> depType : provider.getDependencyTypes()) {
            dependencyToProviders.computeIfAbsent(depType, k -> new CopyOnWriteArrayList<>())
                .add(provider);
        }
    }

    /**
     * Unregister a provider.
     *
     * @param provider the provider to unregister
     */
    public void unregister(ComputedEdgeProvider<?> provider) {
        if (provider == null) return;

        String providerId = provider.getProviderId();
        providerById.remove(providerId);
        allProviders.remove(provider);

        for (List<ComputedEdgeProvider<?>> providers : dependencyToProviders.values()) {
            providers.remove(provider);
        }
    }

    /**
     * Get a provider by its ID.
     *
     * @param providerId the provider ID
     * @return the provider, or empty if not found
     */
    public Optional<ComputedEdgeProvider<?>> getProvider(String providerId) {
        return Optional.ofNullable(providerById.get(providerId));
    }

    /**
     * Get providers that depend on the given entity type.
     *
     * <p>This checks for exact type matches and supertype matches.</p>
     *
     * @param entityType the dependency entity type
     * @return list of providers that depend on this type
     */
    public List<ComputedEdgeProvider<?>> getProvidersForDependency(Class<?> entityType) {
        List<ComputedEdgeProvider<?>> result = new ArrayList<>();

        // Check exact type and supertypes
        for (Map.Entry<Class<?>, List<ComputedEdgeProvider<?>>> entry : dependencyToProviders.entrySet()) {
            if (entry.getKey().isAssignableFrom(entityType)) {
                result.addAll(entry.getValue());
            }
        }

        return result;
    }

    /**
     * Get all registered providers.
     *
     * @return unmodifiable list of all providers
     */
    public List<ComputedEdgeProvider<?>> getAllProviders() {
        return Collections.unmodifiableList(allProviders);
    }

    /**
     * Get providers that handle the given source entity type.
     *
     * @param sourceType the source entity type
     * @return list of providers that handle this source type
     */
    public List<ComputedEdgeProvider<?>> getProvidersForSourceType(Class<?> sourceType) {
        return allProviders.stream()
            .filter(p -> p.supports(sourceType))
            .toList();
    }

    /**
     * Find affected source entities when a dependency changes.
     *
     * <p>This is the main entry point for incremental updates. When an entity
     * that other computed edges depend on is modified, this method identifies
     * which providers need to recompute and which source entities are affected.</p>
     *
     * @param realmId realm identifier
     * @param domain data domain for multi-tenant scoping
     * @param dependencyType type of changed entity
     * @param dependencyId ID of changed entity
     * @return map of provider to affected source entity IDs
     */
    public Map<ComputedEdgeProvider<?>, Set<String>> findAffectedSources(
            String realmId,
            DataDomainInfo domain,
            Class<?> dependencyType,
            String dependencyId) {

        Map<ComputedEdgeProvider<?>, Set<String>> result = new LinkedHashMap<>();

        for (ComputedEdgeProvider<?> provider : getProvidersForDependency(dependencyType)) {
            ComputedEdgeProvider.ComputationContext ctx =
                new ComputedEdgeProvider.ComputationContext(realmId, domain, provider.getProviderId());

            Set<String> affected = provider.getAffectedSourceIds(ctx, dependencyType, dependencyId);
            if (!affected.isEmpty()) {
                result.put(provider, affected);
            }
        }

        return result;
    }

    /**
     * Check if any providers depend on the given entity type.
     *
     * @param entityType the entity type to check
     * @return true if at least one provider depends on this type
     */
    public boolean hasDependentsFor(Class<?> entityType) {
        for (Class<?> depType : dependencyToProviders.keySet()) {
            if (depType.isAssignableFrom(entityType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all dependency types tracked by this registry.
     *
     * @return set of all dependency types
     */
    public Set<Class<?>> getAllDependencyTypes() {
        return Collections.unmodifiableSet(dependencyToProviders.keySet());
    }

    /**
     * Clear all registered providers.
     *
     * <p>This is primarily useful for testing.</p>
     */
    public void clear() {
        allProviders.clear();
        providerById.clear();
        dependencyToProviders.clear();
    }

    /**
     * Get statistics about the registry.
     *
     * @return map of statistic name to value
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProviders", allProviders.size());
        stats.put("dependencyTypes", dependencyToProviders.keySet().size());
        stats.put("providerIds", providerById.keySet());
        return stats;
    }
}
