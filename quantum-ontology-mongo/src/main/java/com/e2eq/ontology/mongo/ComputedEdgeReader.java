package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.*;
import com.e2eq.ontology.metrics.OntologyMetrics;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

/**
 * Read-side facade for retrieving computed edges that honors a provider's
 * {@link MaterializationMode}.
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>{@code EAGER} — straight read from {@link OntologyEdgeRepo}.</li>
 *   <li>{@code LAZY} — check {@link ComputedEdgeCache}; on miss, load the
 *       source entity and call the provider, cache, return.</li>
 *   <li>{@code ONDEMAND} — load the source entity and call the provider every
 *       time. No cache.</li>
 * </ul>
 *
 * <p>For {@code LAZY} and {@code ONDEMAND}, source-entity loading uses the
 * {@link ComputedEdgeRecomputeHandler.SourceEntityLoader} that the application
 * registers at startup.</p>
 *
 * <p>Callers that don't care about mode can keep using the repo directly; this
 * facade is the right entry when correctness across all modes matters.</p>
 */
@ApplicationScoped
public class ComputedEdgeReader {

    @Inject ComputedEdgeRegistry registry;
    @Inject ComputedEdgeRecomputeHandler recomputeHandler;
    @Inject OntologyEdgeRepo edgeRepo;
    @Inject ComputedEdgeCache cache;
    @Inject OntologyMetrics metrics;

    /** Edges produced by {@code provider} for {@code sourceId} under {@code dataDomain}. */
    @SuppressWarnings("unchecked")
    public List<Reasoner.Edge> edgesFor(String realmId,
                                        DataDomain dataDomain,
                                        ComputedEdgeProvider<?> provider,
                                        String sourceId) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(sourceId, "sourceId");

        MaterializationMode mode = provider.getMaterializationMode();
        switch (mode) {
            case EAGER -> {
                return readFromRepo(realmId, dataDomain, provider, sourceId);
            }
            case LAZY -> {
                ComputedEdgeCache.Key key = cacheKey(realmId, dataDomain, provider, sourceId);
                Optional<List<Reasoner.Edge>> hit = cache.get(key);
                if (hit.isPresent()) return hit.get();
                List<Reasoner.Edge> computed =
                        compute(realmId, dataDomain, (ComputedEdgeProvider<Object>) provider, sourceId);
                cache.put(key, computed, provider.getCacheTtlSeconds());
                return computed;
            }
            case ONDEMAND -> {
                return compute(realmId, dataDomain, (ComputedEdgeProvider<Object>) provider, sourceId);
            }
            default -> throw new IllegalStateException("Unhandled mode: " + mode);
        }
    }

    /**
     * Convenience: look up provider by id, then dispatch.
     */
    public List<Reasoner.Edge> edgesFor(String realmId, DataDomain dataDomain,
                                        String providerId, String sourceId) {
        return registry.getProvider(providerId)
                .map(p -> edgesFor(realmId, dataDomain, p, sourceId))
                .orElse(List.of());
    }

    /**
     * Invalidate the cached entry for a (provider, source) pair.
     * No-op for non-LAZY providers.
     */
    public void invalidate(String realmId, DataDomain dataDomain,
                           ComputedEdgeProvider<?> provider, String sourceId) {
        if (provider.getMaterializationMode() != MaterializationMode.LAZY) return;
        cache.invalidate(cacheKey(realmId, dataDomain, provider, sourceId));
    }

    // ────────── internal ──────────

    private List<Reasoner.Edge> readFromRepo(String realmId, DataDomain dataDomain,
                                             ComputedEdgeProvider<?> provider, String sourceId) {
        List<OntologyEdge> stored = edgeRepo.findBySrcAndP(realmId, dataDomain, sourceId, provider.getPredicate());
        List<Reasoner.Edge> out = new ArrayList<>(stored.size());
        for (OntologyEdge e : stored) {
            if (!Boolean.TRUE.equals(e.isDerived())) continue;
            // Only edges produced by THIS provider (when providerId is recorded).
            Object pid = e.getProv() != null ? e.getProv().get("providerId") : null;
            if (pid != null && !provider.getProviderId().equals(pid.toString())) continue;
            out.add(new Reasoner.Edge(
                    e.getSrc(), e.getSrcType(), e.getP(),
                    e.getDst(), e.getDstType(), e.isInferred(),
                    Optional.empty()));
        }
        return out;
    }

    private List<Reasoner.Edge> compute(String realmId, DataDomain dataDomain,
                                        ComputedEdgeProvider<Object> provider, String sourceId) {
        ComputedEdgeRecomputeHandler.SourceEntityLoader loader = sourceLoader();
        if (loader == null) {
            Log.warnf("ComputedEdgeReader: no SourceEntityLoader; provider %s mode requires one",
                    provider.getProviderId());
            return List.of();
        }
        Optional<Object> entity = loader.loadEntity(realmId, dataDomain, provider.getSourceType(), sourceId);
        if (entity.isEmpty()) return List.of();
        DataDomainInfo domainInfo = DataDomainConverter.toInfo(dataDomain);
        long start = System.nanoTime();
        List<Reasoner.Edge> edges = provider.edges(realmId, domainInfo, entity.get());
        metrics.recordProviderInvocation(provider.getProviderId(),
                System.nanoTime() - start,
                edges == null ? 0 : edges.size());
        return edges == null ? List.of() : edges;
    }

    private static ComputedEdgeCache.Key cacheKey(String realmId, DataDomain dataDomain,
                                                  ComputedEdgeProvider<?> provider, String sourceId) {
        String tenant = dataDomain == null ? null : dataDomain.getTenantId();
        return new ComputedEdgeCache.Key(provider.getProviderId(), realmId, tenant, sourceId);
    }

    /** Same trick as BulkRecomputeService for accessing the registered loader. */
    protected ComputedEdgeRecomputeHandler.SourceEntityLoader sourceLoader() {
        try {
            var f = ComputedEdgeRecomputeHandler.class.getDeclaredField("sourceEntityLoader");
            f.setAccessible(true);
            return (ComputedEdgeRecomputeHandler.SourceEntityLoader) f.get(recomputeHandler);
        } catch (Exception e) {
            return null;
        }
    }
}
