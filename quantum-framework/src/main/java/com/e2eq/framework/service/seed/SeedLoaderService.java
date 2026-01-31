package com.e2eq.framework.service.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Objects;

/**
 * CDI-managed service for seed loading operations.
 * Provides dependency injection for SeedLoader and auto-discovers seed sources.
 *
 * This service replaces manual SeedLoader.builder() construction throughout the codebase.
 */
@ApplicationScoped
public class SeedLoaderService {

    @Inject
    SeedRepository seedRepository;

    @Inject
    SeedRegistry seedRegistry;

    @Inject
    Instance<SeedSource> seedSources; // Auto-discover CDI-managed seed sources

    @Inject
    Instance<SeedVariableResolver> variableResolvers; // Auto-discover CDI-managed variable resolvers

    @Inject
    Instance<SeedRecordListener> recordListeners; // Auto-discover CDI-managed record listeners

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SeedDiscoveryService seedDiscoveryService;

    @Inject
    SeedMetrics seedMetrics;

    @Inject
    SeedDatasetValidator seedDatasetValidator;

    @ConfigProperty(name = "quantum.seed.conflict-policy", defaultValue = "SEED_WINS")
    SeedConflictPolicy conflictPolicy;

    @ConfigProperty(name = "quantum.seed.batch.size", defaultValue = "1000")
    int batchSize;

    /**
     * Creates a SeedLoader instance with all configured dependencies.
     * Auto-discovers CDI-managed seed sources (FileSeedSource and ClasspathSeedSource are now CDI beans).
     *
     * @param context the seed context (unused, kept for API compatibility)
     * @return a configured SeedLoader instance
     */
    public SeedLoader createLoader(SeedContext context) {
        if (conflictPolicy == SeedConflictPolicy.SEED_WINS) {
            Log.warn("SeedLoaderService: conflict policy is SEED_WINS — this may overwrite existing data when checksums differ. Use only in dev/test.");
        }

        SeedLoader.Builder builder = SeedLoader.builder()
                .seedRepository(seedRepository)
                .seedRegistry(seedRegistry)
                .objectMapper(objectMapper)
                .conflictPolicy(conflictPolicy)
                .batchSize(batchSize)
                .seedMetrics(seedMetrics)
                .seedDatasetValidator(seedDatasetValidator);

        // Add auto-discovered CDI-managed seed sources
        // FileSeedSource and ClasspathSeedSource are now @ApplicationScoped beans
        for (SeedSource source : seedSources) {
            Log.debugf("SeedLoaderService: adding CDI-discovered seed source: %s", source.getId());
            builder.addSeedSource(source);
        }

        // Add auto-discovered CDI-managed variable resolvers for string interpolation
        for (SeedVariableResolver resolver : variableResolvers) {
            Log.debugf("SeedLoaderService: adding CDI-discovered variable resolver: %s (priority=%d)",
                    resolver.getClass().getSimpleName(), resolver.priority());
            builder.addVariableResolver(resolver);
        }

        // Add auto-discovered CDI-managed record listeners
        for (SeedRecordListener listener : recordListeners) {
            Log.debugf("SeedLoaderService: adding CDI-discovered record listener: %s (priority=%d, async=%s)",
                    listener.getClass().getSimpleName(), listener.priority(), listener.async());
            builder.addRecordListener(listener);
        }

        return builder.build();
    }

    /**
     * Applies seed packs to the given context.
     * This method is transactional to ensure data consistency.
     *
     * @param context the seed context
     * @param packRefs the seed pack references to apply
     */
    @Transactional
    public void applySeeds(SeedContext context, List<SeedPackRef> packRefs) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(packRefs, "packRefs");

        if (packRefs.isEmpty()) {
            Log.infof("SeedLoaderService: no seed packs to apply for realm %s", context.getRealm());
            return;
        }

        long startTime = System.currentTimeMillis();
        boolean success = false;

        try {
            // Set MDC context for structured logging
            org.slf4j.MDC.put("realm", context.getRealm());
            org.slf4j.MDC.put("seedPackCount", String.valueOf(packRefs.size()));

            SeedLoader loader = createLoader(context);
            loader.apply(packRefs, context);
            success = true;

            // Extract record count from loader if available
            // For now, we'll track this at the dataset level via metrics

            Log.infof("SeedLoaderService: successfully applied %d seed pack(s) to realm %s",
                    packRefs.size(), context.getRealm());
        } catch (Exception e) {
            Log.errorf(e, "SeedLoaderService: failed to apply seed packs to realm %s: %s",
                    context.getRealm(), e.getMessage());
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - startTime;

            // Record metrics for each pack (simplified - in practice, loader would provide per-pack metrics)
            for (SeedPackRef ref : packRefs) {
                seedMetrics.recordSeedApplication(
                        ref.getName(),
                        ref.getSpecification() != null ? ref.getSpecification() : "latest",
                        context.getRealm(),
                        success,
                        duration / packRefs.size(), // approximate per-pack duration
                        0 // record count would come from loader
                );
            }

            // Clear MDC
            org.slf4j.MDC.clear();
        }
    }

    /**
     * Applies an archetype to the given context.
     * This method is transactional to ensure data consistency.
     *
     * @param context the seed context
     * @param archetypeName the archetype name to apply
     */
    @Transactional
    public void applyArchetype(SeedContext context, String archetypeName) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(archetypeName, "archetypeName");

        SeedLoader loader = createLoader(context);
        loader.applyArchetype(archetypeName, context);
    }

    /**
     * Gets the configured seed repository.
     *
     * @return the seed repository
     */
    public SeedRepository getSeedRepository() {
        return seedRepository;
    }

    /**
     * Gets the configured seed registry.
     *
     * @return the seed registry
     */
    public SeedRegistry getSeedRegistry() {
        return seedRegistry;
    }
}
