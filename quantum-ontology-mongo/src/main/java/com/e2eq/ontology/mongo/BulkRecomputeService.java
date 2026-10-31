package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.ComputedEdgeProvider;
import com.e2eq.ontology.core.ComputedEdgeRegistry;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.metrics.OntologyMetrics;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.*;

/**
 * Bulk recompute / reconciliation for {@link ComputedEdgeProvider}s.
 *
 * <p>Given a provider id and a {@link DataDomain}, walks all source entities
 * of the provider's source type (via a registered {@link SourceEntityEnumerator}),
 * recomputes their target sets, diffs against the persisted edges, and either
 * reports the deltas (dry-run) or applies them.</p>
 *
 * <p>Use cases:</p>
 * <ul>
 *   <li>A provider's logic changed and existing edges need to converge.</li>
 *   <li>Audit: confirm in-store edges still match what the provider would
 *       produce today.</li>
 *   <li>Targeted repair: pass {@code sourceId} to recompute one entity.</li>
 * </ul>
 */
@ApplicationScoped
public class BulkRecomputeService {

    @Inject ComputedEdgeRegistry registry;
    @Inject ComputedEdgeRecomputeHandler recomputeHandler;
    @Inject OntologyEdgeRepo edgeRepo;
    @Inject OntologyMetrics metrics;
    @Inject Instance<SourceEntityEnumerator> enumerators;

    public Result recompute(Request req) {
        Objects.requireNonNull(req, "request");
        Objects.requireNonNull(req.providerId, "providerId");
        Objects.requireNonNull(req.dataDomain, "dataDomain");

        Optional<ComputedEdgeProvider<?>> providerOpt = registry.getProvider(req.providerId);
        if (providerOpt.isEmpty()) {
            return Result.failure("Provider not found: " + req.providerId);
        }
        ComputedEdgeProvider<?> provider = providerOpt.get();
        Class<?> sourceType = provider.getSourceType();

        SourceEntityEnumerator enumerator = pickEnumerator(sourceType);
        // Enumerator is optional only when sourceId is supplied.
        if (enumerator == null && (req.sourceId == null || req.sourceId.isBlank())) {
            return Result.failure(
                    "No SourceEntityEnumerator registered for " + sourceType.getName() +
                    " — supply --source-id to recompute a single entity, or register an enumerator.");
        }

        List<String> idsToProcess = (req.sourceId != null && !req.sourceId.isBlank())
                ? List.of(req.sourceId)
                : drainAll(enumerator, req, sourceType);

        DataDomainInfo domainInfo = DataDomainConverter.toInfo(req.dataDomain);
        Result result = new Result();
        result.providerId = req.providerId;
        result.sourceType = sourceType.getSimpleName();
        result.dryRun = req.dryRun;

        for (String sourceId : idsToProcess) {
            try {
                SourceDelta delta = computeDelta(provider, req.realmId, req.dataDomain, domainInfo, sourceId);
                if (delta == null) continue;
                result.add(delta);

                if (!req.dryRun && (delta.added > 0 || delta.removed > 0)) {
                    boolean ok = recomputeHandler.recomputeManually(
                            req.realmId, req.dataDomain, req.providerId, sourceId);
                    if (!ok) {
                        result.errors.add("recomputeManually returned false for " + sourceId);
                    }
                }
            } catch (Exception e) {
                Log.warnf(e, "BulkRecomputeService: failed for %s/%s", req.providerId, sourceId);
                result.errors.add("source " + sourceId + ": " + e.getMessage());
            }
        }
        return result;
    }

    private SourceEntityEnumerator pickEnumerator(Class<?> sourceType) {
        for (SourceEntityEnumerator e : enumerators) {
            if (e.supports(sourceType)) return e;
        }
        return null;
    }

    private List<String> drainAll(SourceEntityEnumerator enumerator, Request req, Class<?> sourceType) {
        List<String> all = new ArrayList<>();
        String cursor = null;
        int batch = req.batchSize > 0 ? req.batchSize : 500;
        while (true) {
            List<String> page = enumerator.listIds(req.realmId, req.dataDomain, sourceType, cursor, batch);
            if (page == null || page.isEmpty()) break;
            all.addAll(page);
            cursor = page.get(page.size() - 1);
            if (page.size() < batch) break;
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private SourceDelta computeDelta(ComputedEdgeProvider<?> provider,
                                     String realmId,
                                     DataDomain dataDomain,
                                     DataDomainInfo domainInfo,
                                     String sourceId) {
        ComputedEdgeRecomputeHandler.SourceEntityLoader loader = sourceLoader();
        if (loader == null) return null;

        Optional<Object> entityOpt = loader.loadEntity(realmId, dataDomain, provider.getSourceType(), sourceId);
        if (entityOpt.isEmpty()) return null;

        // Recompute target set.
        List<Reasoner.Edge> newEdges =
                ((ComputedEdgeProvider<Object>) provider).edges(realmId, domainInfo, entityOpt.get());
        Set<String> newTargets = new HashSet<>();
        for (Reasoner.Edge e : newEdges) newTargets.add(e.dstId());

        // Existing edges produced by this provider for this source.
        String predicate = provider.getPredicate();
        String wantedId = provider.getProviderId();
        Set<String> existingTargets = new HashSet<>();
        for (OntologyEdge e : edgeRepo.findBySrcAndP(dataDomain, sourceId, predicate)) {
            if (!Boolean.TRUE.equals(e.isDerived())) continue;
            String pid = ComputedEdgeReader.extractProviderId(e.getProv());
            if (pid == null || wantedId.equals(pid)) {
                existingTargets.add(e.getDst());
            }
        }

        Set<String> added = new HashSet<>(newTargets);
        added.removeAll(existingTargets);
        Set<String> removed = new HashSet<>(existingTargets);
        removed.removeAll(newTargets);

        SourceDelta d = new SourceDelta();
        d.sourceId = sourceId;
        d.added = added.size();
        d.removed = removed.size();
        d.unchanged = newTargets.size() - added.size();
        return d;
    }

    /** Pulled out for test override; in prod, the recompute handler holds the loader. */
    protected ComputedEdgeRecomputeHandler.SourceEntityLoader sourceLoader() {
        try {
            var f = ComputedEdgeRecomputeHandler.class.getDeclaredField("sourceEntityLoader");
            f.setAccessible(true);
            return (ComputedEdgeRecomputeHandler.SourceEntityLoader) f.get(recomputeHandler);
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Request {
        public String realmId;
        public DataDomain dataDomain;
        public String providerId;
        public String sourceId;       // optional: recompute one source only
        public int batchSize = 500;
        public boolean dryRun = false;
    }

    public static final class SourceDelta {
        public String sourceId;
        public int added;
        public int removed;
        public int unchanged;
    }

    public static final class Result {
        public String providerId;
        public String sourceType;
        public boolean dryRun;
        public int sourcesProcessed;
        public int sourcesWithChanges;
        public long totalAdded;
        public long totalRemoved;
        public List<SourceDelta> changedSources = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public String error;

        static Result failure(String msg) {
            Result r = new Result();
            r.error = msg;
            return r;
        }

        void add(SourceDelta d) {
            sourcesProcessed++;
            totalAdded += d.added;
            totalRemoved += d.removed;
            if (d.added > 0 || d.removed > 0) {
                sourcesWithChanges++;
                changedSources.add(d);
            }
        }
    }
}
