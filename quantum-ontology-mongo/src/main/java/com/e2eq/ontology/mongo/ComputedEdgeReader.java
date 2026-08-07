package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.*;
import com.e2eq.ontology.metrics.OntologyMetrics;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
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
 * registers at startup. Destination-keyed lookups additionally need a
 * {@link SourceEntityEnumerator} so candidate sources can be walked when no
 * rows exist in {@code ontology_edges}.</p>
 *
 * <p><b>Why this facade exists:</b> policy rewrites ({@code hasEdge} /
 * {@code ListQueryRewriter}), MCP graph tools, and similar callers query edges
 * both by source ({@code findBySrcAndP} / {@code dstIdsBySrc}) and by
 * destination ({@code findByDst*} / {@code srcIdsByDst}). Pure repo reads miss
 * unmaterialized LAZY/ONDEMAND edges. Use this facade (or APIs wired through
 * it) whenever correctness across all materialization modes matters.</p>
 */
@ApplicationScoped
public class ComputedEdgeReader {

    /** Hard cap on source IDs walked for a single inverse LAZY/ONDEMAND query. */
    public static final int DEFAULT_INVERSE_SOURCE_SCAN_LIMIT = 10_000;

    @Inject ComputedEdgeRegistry registry;
    @Inject ComputedEdgeRecomputeHandler recomputeHandler;
    @Inject OntologyEdgeRepo edgeRepo;
    @Inject ComputedEdgeCache cache;
    @Inject OntologyMetrics metrics;
    @Inject Instance<SourceEntityEnumerator> enumerators;

    /** CDI */
    public ComputedEdgeReader() {}

    /** Test-friendly constructor. */
    ComputedEdgeReader(ComputedEdgeRegistry registry,
                       ComputedEdgeRecomputeHandler recomputeHandler,
                       OntologyEdgeRepo edgeRepo,
                       ComputedEdgeCache cache,
                       OntologyMetrics metrics,
                       Instance<SourceEntityEnumerator> enumerators) {
        this.registry = registry;
        this.recomputeHandler = recomputeHandler;
        this.edgeRepo = edgeRepo;
        this.cache = cache;
        this.metrics = metrics;
        this.enumerators = enumerators;
    }

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
     * Destination IDs reachable from {@code sourceId} via {@code predicate},
     * unioning EAGER store rows with LAZY/ONDEMAND provider computation.
     */
    public Set<String> dstIdsBySrc(String realmId, DataDomain dataDomain,
                                   String predicate, String sourceId) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(sourceId, "sourceId");

        Set<String> ids = new LinkedHashSet<>(edgeRepo.dstIdsBySrc(dataDomain, predicate, sourceId));
        for (ComputedEdgeProvider<?> provider : providersForPredicate(predicate)) {
            if (provider.getMaterializationMode() == MaterializationMode.EAGER) {
                continue; // already represented in the store (when write path ran)
            }
            for (Reasoner.Edge e : edgesFor(realmId, dataDomain, provider, sourceId)) {
                if (predicate.equals(e.p()) && sourceId.equals(e.srcId()) && e.dstId() != null) {
                    ids.add(e.dstId());
                }
            }
        }
        return ids;
    }

    /**
     * Source IDs that have an edge with {@code predicate} pointing to {@code dstId}.
     *
     * <p>Unions store rows with LAZY/ONDEMAND providers. Non-EAGER providers require a
     * {@link SourceEntityEnumerator} for their source type; without one, only store
     * rows are returned and a warning is logged (policy {@code hasEdge} would otherwise
     * silently miss computed edges).</p>
     */
    public Set<String> srcIdsByDst(String realmId, DataDomain dataDomain,
                                   String predicate, String dstId) {
        return srcIdsByDst(realmId, dataDomain, predicate, dstId, DEFAULT_INVERSE_SOURCE_SCAN_LIMIT);
    }

    public Set<String> srcIdsByDst(String realmId, DataDomain dataDomain,
                                   String predicate, String dstId, int sourceScanLimit) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(dstId, "dstId");
        int limit = Math.max(1, sourceScanLimit);

        Set<String> ids = new LinkedHashSet<>(edgeRepo.srcIdsByDst(dataDomain, predicate, dstId));
        for (ComputedEdgeProvider<?> provider : providersForPredicate(predicate)) {
            if (provider.getMaterializationMode() == MaterializationMode.EAGER) {
                continue;
            }
            ids.addAll(srcIdsByDstFromProvider(realmId, dataDomain, provider, dstId, limit));
        }
        return ids;
    }

    /**
     * Bulk form of {@link #srcIdsByDst(String, DataDomain, String, String)} for a set of destinations.
     */
    public Set<String> srcIdsByDstIn(String realmId, DataDomain dataDomain,
                                     String predicate, Collection<String> dstIds) {
        if (dstIds == null || dstIds.isEmpty()) return Set.of();
        Set<String> ids = new LinkedHashSet<>(edgeRepo.srcIdsByDstIn(dataDomain, predicate, dstIds));
        Set<String> wanted = new HashSet<>(dstIds);
        for (ComputedEdgeProvider<?> provider : providersForPredicate(predicate)) {
            if (provider.getMaterializationMode() == MaterializationMode.EAGER) {
                continue;
            }
            for (String srcId : enumerateSourceIds(realmId, dataDomain, provider, DEFAULT_INVERSE_SOURCE_SCAN_LIMIT)) {
                for (Reasoner.Edge e : edgesFor(realmId, dataDomain, provider, srcId)) {
                    if (predicate.equals(e.p()) && e.dstId() != null && wanted.contains(e.dstId()) && e.srcId() != null) {
                        ids.add(e.srcId());
                    }
                }
            }
        }
        return ids;
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

    private List<ComputedEdgeProvider<?>> providersForPredicate(String predicate) {
        List<ComputedEdgeProvider<?>> out = new ArrayList<>();
        for (ComputedEdgeProvider<?> p : registry.getAllProviders()) {
            if (predicate.equals(p.getPredicate())) {
                out.add(p);
            }
        }
        return out;
    }

    private Set<String> srcIdsByDstFromProvider(String realmId, DataDomain dataDomain,
                                                ComputedEdgeProvider<?> provider,
                                                String dstId, int sourceScanLimit) {
        Set<String> ids = new LinkedHashSet<>();
        for (String srcId : enumerateSourceIds(realmId, dataDomain, provider, sourceScanLimit)) {
            for (Reasoner.Edge e : edgesFor(realmId, dataDomain, provider, srcId)) {
                if (dstId.equals(e.dstId()) && e.srcId() != null) {
                    ids.add(e.srcId());
                }
            }
        }
        return ids;
    }

    private List<String> enumerateSourceIds(String realmId, DataDomain dataDomain,
                                            ComputedEdgeProvider<?> provider, int sourceScanLimit) {
        SourceEntityEnumerator enumerator = pickEnumerator(provider.getSourceType());
        if (enumerator == null) {
            Log.warnf(
                    "ComputedEdgeReader: no SourceEntityEnumerator for %s (provider %s, mode %s). " +
                    "Destination-keyed reads (srcIdsByDst / hasEdge) will miss unmaterialized edges. " +
                    "Register an enumerator or use MaterializationMode.EAGER for this predicate.",
                    provider.getSourceType().getName(),
                    provider.getProviderId(),
                    provider.getMaterializationMode());
            return List.of();
        }

        List<String> all = new ArrayList<>();
        String after = null;
        int pageSize = Math.min(500, sourceScanLimit);
        while (all.size() < sourceScanLimit) {
            int remaining = sourceScanLimit - all.size();
            List<String> page = enumerator.listIds(
                    realmId, dataDomain, provider.getSourceType(), after, Math.min(pageSize, remaining));
            if (page == null || page.isEmpty()) break;
            all.addAll(page);
            after = page.get(page.size() - 1);
            if (page.size() < pageSize) break;
        }
        if (all.size() >= sourceScanLimit) {
            Log.warnf(
                    "ComputedEdgeReader: inverse source scan hit limit %d for provider %s predicate %s; " +
                    "results may be incomplete. Prefer EAGER materialization for high-cardinality inverse queries.",
                    sourceScanLimit, provider.getProviderId(), provider.getPredicate());
        }
        return all;
    }

    private SourceEntityEnumerator pickEnumerator(Class<?> sourceType) {
        if (enumerators == null || enumerators.isUnsatisfied()) return null;
        for (SourceEntityEnumerator e : enumerators) {
            if (e.supports(sourceType)) return e;
        }
        return null;
    }

    private List<Reasoner.Edge> readFromRepo(String realmId, DataDomain dataDomain,
                                             ComputedEdgeProvider<?> provider, String sourceId) {
        List<OntologyEdge> stored = edgeRepo.findBySrcAndP(realmId, dataDomain, sourceId, provider.getPredicate());
        List<Reasoner.Edge> out = new ArrayList<>(stored.size());
        String wantedId = provider.getProviderId();
        for (OntologyEdge e : stored) {
            if (!Boolean.TRUE.equals(e.isDerived())) continue;
            String pid = extractProviderId(e.getProv());
            // If we know which provider made this edge, filter strictly. Truly
            // legacy edges with no recorded providerId are passed through (the
            // alternative is dropping them silently, which is worse).
            if (pid != null && !wantedId.equals(pid)) continue;
            out.add(new Reasoner.Edge(
                    e.getSrc(), e.getSrcType(), e.getP(),
                    e.getDst(), e.getDstType(), e.isInferred(),
                    Optional.empty()));
        }
        return out;
    }

    /**
     * Provider id can live in two shapes depending on the write path:
     *
     * <ul>
     *   <li>{@code OntologyMaterializer.upsertDerived} stores
     *       {@code {rule:"computed", inputs:{providerId:..., ...}}}</li>
     *   <li>{@code ComputedEdgeRecomputeHandler.insertComputedEdge} stores
     *       {@code {rule:"computed", providerId:..., ...}} (flat)</li>
     * </ul>
     *
     * Plus the M2.D5 split-provenance marker
     * {@code {providerId:..., split:true}}. Returns {@code null} when no
     * id can be located (truly legacy edges).
     */
    @SuppressWarnings("unchecked")
    static String extractProviderId(java.util.Map<String, Object> prov) {
        if (prov == null || prov.isEmpty()) return null;
        Object top = prov.get("providerId");
        if (top != null) return top.toString();
        Object inputs = prov.get("inputs");
        if (inputs instanceof java.util.Map<?, ?> m) {
            Object nested = ((java.util.Map<String, Object>) m).get("providerId");
            if (nested != null) return nested.toString();
        }
        return null;
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
        String org = dataDomain == null ? null : dataDomain.getOrgRefName();
        String acct = dataDomain == null ? null : dataDomain.getAccountNum();
        String tenant = dataDomain == null ? null : dataDomain.getTenantId();
        int seg = dataDomain == null ? 0 : dataDomain.getDataSegment();
        return new ComputedEdgeCache.Key(provider.getProviderId(), realmId, org, acct, tenant, seg, sourceId);
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
