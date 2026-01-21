package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.*;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

/**
 * Handler for recomputing computed edges when dependency entities change.
 *
 * <p>When an entity that computed edges depend on is modified (e.g., a Territory's
 * LocationList), this handler:</p>
 * <ol>
 *   <li>Identifies which providers are affected</li>
 *   <li>Finds which source entities need edge recomputation</li>
 *   <li>Triggers recomputation for each affected source</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <p>This handler is typically called from entity persistence hooks when a
 * dependency entity is modified:</p>
 * <pre>{@code
 * @Inject ComputedEdgeRecomputeHandler recomputeHandler;
 *
 * // In a Territory post-persist hook:
 * recomputeHandler.onDependencyModified(realmId, dataDomain, Territory.class, territoryId);
 * }</pre>
 *
 * <p>Applications must implement the source entity loading mechanism by providing
 * a {@link SourceEntityLoader} implementation.</p>
 */
@ApplicationScoped
public class ComputedEdgeRecomputeHandler {

    @Inject
    ComputedEdgeRegistry registry;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    OntologyMaterializer materializer;

    /** Optional loader for source entities - must be provided by application */
    private SourceEntityLoader sourceEntityLoader;

    /**
     * Interface for loading source entities by type and ID.
     *
     * <p>Applications must implement this and call {@link #setSourceEntityLoader}
     * to enable automatic recomputation.</p>
     */
    public interface SourceEntityLoader {
        /**
         * Load an entity by type and ID.
         *
         * @param realmId realm identifier
         * @param domain data domain for scoping
         * @param entityType the entity class
         * @param entityId the entity ID
         * @return the loaded entity, or empty if not found
         */
        Optional<Object> loadEntity(String realmId, DataDomain domain,
                                    Class<?> entityType, String entityId);
    }

    /**
     * Set the source entity loader.
     *
     * <p>This must be called during application startup to enable automatic
     * source entity loading during recomputation.</p>
     *
     * @param loader the entity loader
     */
    public void setSourceEntityLoader(SourceEntityLoader loader) {
        this.sourceEntityLoader = loader;
    }

    /**
     * Called when a dependency entity is modified.
     *
     * <p>This triggers recomputation of computed edges for all affected source entities.</p>
     *
     * @param realmId realm identifier
     * @param dataDomain data domain for scoping
     * @param dependencyType type of the modified entity
     * @param dependencyId ID of the modified entity
     * @return summary of recomputation results
     */
    public RecomputeResult onDependencyModified(
            String realmId,
            DataDomain dataDomain,
            Class<?> dependencyType,
            String dependencyId) {

        DataDomainInfo domainInfo = DataDomainConverter.toInfo(dataDomain);

        Log.infof("ComputedEdgeRecomputeHandler: dependency modified %s[%s]",
            dependencyType.getSimpleName(), dependencyId);

        // Find affected providers and source entities
        Map<ComputedEdgeProvider<?>, Set<String>> affected =
            registry.findAffectedSources(realmId, domainInfo, dependencyType, dependencyId);

        if (affected.isEmpty()) {
            Log.debugf("No computed edge providers affected by %s[%s]",
                dependencyType.getSimpleName(), dependencyId);
            return new RecomputeResult(0, 0, List.of());
        }

        int providersProcessed = 0;
        int sourcesRecomputed = 0;
        List<String> errors = new ArrayList<>();

        for (Map.Entry<ComputedEdgeProvider<?>, Set<String>> entry : affected.entrySet()) {
            ComputedEdgeProvider<?> provider = entry.getKey();
            Set<String> sourceIds = entry.getValue();

            Log.infof("Provider %s has %d affected sources",
                provider.getProviderId(), sourceIds.size());

            providersProcessed++;

            for (String sourceId : sourceIds) {
                try {
                    boolean success = recomputeEdgesForSource(
                        realmId, dataDomain, domainInfo, provider, sourceId);
                    if (success) {
                        sourcesRecomputed++;
                    }
                } catch (Exception e) {
                    String error = String.format("Failed to recompute edges for %s[%s] via %s: %s",
                        provider.getSourceType().getSimpleName(), sourceId,
                        provider.getProviderId(), e.getMessage());
                    Log.warn(error, e);
                    errors.add(error);
                }
            }
        }

        Log.infof("ComputedEdgeRecomputeHandler: processed %d providers, recomputed %d sources, %d errors",
            providersProcessed, sourcesRecomputed, errors.size());

        return new RecomputeResult(providersProcessed, sourcesRecomputed, errors);
    }

    /**
     * Recompute edges for a specific source entity and provider.
     *
     * @param realmId realm identifier
     * @param dataDomain data domain for scoping
     * @param provider the provider to use
     * @param sourceId the source entity ID
     * @return true if recomputation was successful
     */
    @SuppressWarnings("unchecked")
    private boolean recomputeEdgesForSource(
            String realmId,
            DataDomain dataDomain,
            DataDomainInfo domainInfo,
            ComputedEdgeProvider<?> provider,
            String sourceId) {

        // Load source entity
        if (sourceEntityLoader == null) {
            Log.warnf("Cannot recompute edges: no SourceEntityLoader configured");
            return false;
        }

        Optional<Object> entityOpt = sourceEntityLoader.loadEntity(
            realmId, dataDomain, provider.getSourceType(), sourceId);

        if (entityOpt.isEmpty()) {
            Log.debugf("Source entity %s[%s] not found, skipping recomputation",
                provider.getSourceType().getSimpleName(), sourceId);
            return false;
        }

        Object entity = entityOpt.get();

        // Compute new edges
        List<Reasoner.Edge> newEdges = provider.edges(realmId, domainInfo, entity);

        Log.debugf("Recomputed %d edges for %s[%s] via %s",
            newEdges.size(), provider.getSourceType().getSimpleName(), sourceId, provider.getProviderId());

        // Delete old computed edges for this source + predicate
        String predicate = provider.getPredicate();
        deleteComputedEdges(dataDomain, sourceId, predicate, provider.getProviderId());

        // Insert new computed edges
        for (Reasoner.Edge edge : newEdges) {
            insertComputedEdge(dataDomain, edge, provider.getProviderId());
        }

        return true;
    }

    /**
     * Delete computed edges for a source entity and predicate.
     */
    private void deleteComputedEdges(DataDomain dataDomain, String sourceId,
                                      String predicate, String providerId) {
        try {
            List<OntologyEdge> existing = edgeRepo.findBySrcAndP(dataDomain, sourceId, predicate);

            for (OntologyEdge edge : existing) {
                // Only delete edges created by this provider
                if (isComputedByProvider(edge, providerId)) {
                    edgeRepo.delete(edge.getId());
                }
            }
        } catch (Exception e) {
            Log.warnf("Error deleting computed edges for %s/%s: %s", sourceId, predicate, e.getMessage());
        }
    }

    /**
     * Check if an edge was computed by a specific provider.
     *
     * <p>This checks multiple indicators:</p>
     * <ol>
     *   <li>Provenance providerId matches the given provider</li>
     *   <li>Provenance rule == "computed"</li>
     *   <li>Edge has derived=true flag (fallback for edges materialized through OntologyMaterializer)</li>
     * </ol>
     */
    private boolean isComputedByProvider(OntologyEdge edge, String providerId) {
        // First, check provenance if available
        if (edge.getProv() != null && !edge.getProv().isEmpty()) {
            Object providerIdObj = edge.getProv().get("providerId");
            if (providerIdObj != null) {
                return providerId.equals(providerIdObj.toString());
            }

            // Check rule field for "computed" marker
            Object rule = edge.getProv().get("rule");
            if ("computed".equals(rule)) {
                return true;
            }
        }

        // Fallback: check if edge is derived (computed edges are stored with derived=true)
        // This handles edges that went through OntologyMaterializer
        return edge.isDerived();
    }

    /**
     * Insert a computed edge.
     */
    private void insertComputedEdge(DataDomain dataDomain, Reasoner.Edge edge, String providerId) {
        OntologyEdge ontEdge = new OntologyEdge();
        ontEdge.setDataDomain(dataDomain);
        ontEdge.setSrc(edge.srcId());
        ontEdge.setSrcType(edge.srcType());
        ontEdge.setP(edge.p());
        ontEdge.setDst(edge.dstId());
        ontEdge.setDstType(edge.dstType());
        ontEdge.setInferred(false);
        ontEdge.setDerived(true);  // Mark as computed/derived
        ontEdge.setTs(new Date());

        if (edge.prov().isPresent()) {
            ontEdge.setProv(edge.prov().get().inputs());
        } else {
            ontEdge.setProv(Map.of(
                "rule", "computed",
                "providerId", providerId,
                "computedAt", new Date()
            ));
        }

        edgeRepo.save(ontEdge);
    }

    /**
     * Manually trigger recomputation for a specific source entity and provider.
     *
     * <p>This is useful for testing or manual intervention.</p>
     *
     * @param realmId realm identifier
     * @param dataDomain data domain
     * @param providerId the provider ID
     * @param sourceId the source entity ID
     * @return true if successful
     */
    public boolean recomputeManually(String realmId, DataDomain dataDomain,
                                      String providerId, String sourceId) {
        Optional<ComputedEdgeProvider<?>> providerOpt = registry.getProvider(providerId);
        if (providerOpt.isEmpty()) {
            Log.warnf("Provider %s not found", providerId);
            return false;
        }

        DataDomainInfo domainInfo = DataDomainConverter.toInfo(dataDomain);
        return recomputeEdgesForSource(realmId, dataDomain, domainInfo, providerOpt.get(), sourceId);
    }

    /**
     * Result of a recomputation operation.
     */
    public record RecomputeResult(
        /** Number of providers that were processed */
        int providersProcessed,

        /** Number of source entities whose edges were recomputed */
        int sourcesRecomputed,

        /** List of error messages (if any) */
        List<String> errors
    ) {
        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }
    }
}
