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
        realmId = normalizeRealmId(realmId);

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
     * unioning store rows (excluding stale non-EAGER provider rows) with
     * LAZY/ONDEMAND provider computation.
     */
    public Set<String> dstIdsBySrc(String realmId, DataDomain dataDomain,
                                   String predicate, String sourceId) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(sourceId, "sourceId");
        realmId = normalizeRealmId(realmId);

        Set<String> ids = storeDstIdsExcludingNonEager(dataDomain, predicate, sourceId);
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
     * <p>Unions store rows with LAZY/ONDEMAND providers. Store rows attributed to a
     * currently non-EAGER provider are excluded so a mode switch EAGER→LAZY/ONDEMAND
     * does not keep granting access via stale materialized rows. Non-EAGER providers
     * require a {@link SourceEntityEnumerator}; without one, only eligible store rows
     * are returned and a warning is logged.</p>
     */
    public Set<String> srcIdsByDst(String realmId, DataDomain dataDomain,
                                   String predicate, String dstId) {
        return srcIdsByDst(realmId, dataDomain, predicate, dstId, DEFAULT_INVERSE_SOURCE_SCAN_LIMIT);
    }

    public Set<String> srcIdsByDst(String realmId, DataDomain dataDomain,
                                   String predicate, String dstId, int sourceScanLimit) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(dstId, "dstId");
        realmId = normalizeRealmId(realmId);
        int limit = Math.max(1, sourceScanLimit);

        Set<String> ids = storeSrcIdsExcludingNonEager(dataDomain, predicate, dstId);
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
        realmId = normalizeRealmId(realmId);
        Set<String> wanted = new HashSet<>(dstIds);
        Set<String> ids = new LinkedHashSet<>();
        for (String dstId : wanted) {
            ids.addAll(storeSrcIdsExcludingNonEager(dataDomain, predicate, dstId));
        }
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
     * Normalize a realm/tenant id the same way {@code OntologyEdgeRepo} does:
     * MongoDB database names cannot contain dots, so dots become hyphens.
     * Package-visible for policy-bridge realm hints and tests.
     */
    public static String normalizeRealmId(String realmOrTenantId) {
        if (realmOrTenantId == null || realmOrTenantId.isBlank()) return realmOrTenantId;
        return realmOrTenantId.replace('.', '-');
    }

    /**
     * Full relationship edges leaving {@code sourceId} for graph UIs / MCP discovery.
     * Unions store rows (excluding stale non-EAGER provider rows) with LAZY/ONDEMAND
     * provider computation. Optional {@code predicate} filters both sides.
     *
     * <p><b>Uniqueness:</b> returns a list with set semantics on edge identity
     * {@code (srcId, predicate, dstId)} — first occurrence wins. Callers must not
     * assume bag/multiset semantics; store∪compute can otherwise double-count.</p>
     */
    public List<Reasoner.Edge> relationshipEdgesFromSrc(String realmId, DataDomain dataDomain,
                                                        String sourceId, String predicate) {
        Objects.requireNonNull(sourceId, "sourceId");
        realmId = normalizeRealmId(realmId);
        String pred = blankToNull(predicate);
        Set<String> nonEager = allNonEagerProviderIds();

        // LinkedHashMap = ordered set of edges by (src, p, dst)
        Map<String, Reasoner.Edge> unique = new LinkedHashMap<>();
        List<OntologyEdge> store = pred != null
                ? edgeRepo.findBySrcAndP(dataDomain, sourceId, pred)
                : edgeRepo.findBySrc(dataDomain, sourceId);
        for (OntologyEdge e : store) {
            if (isStaleNonEagerStoreRow(e, nonEager)) continue;
            putEdge(unique, toReasonerEdge(e));
        }

        for (ComputedEdgeProvider<?> provider : registry.getAllProviders()) {
            if (provider.getMaterializationMode() == MaterializationMode.EAGER) continue;
            if (pred != null && !pred.equals(provider.getPredicate())) continue;
            for (Reasoner.Edge e : edgesFor(realmId, dataDomain, provider, sourceId)) {
                if (sourceId.equals(e.srcId())) putEdge(unique, e);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Full relationship edges pointing at {@code dstId} for graph UIs / MCP discovery.
     * Unions store rows (excluding stale non-EAGER provider rows) with LAZY/ONDEMAND
     * inverse scans. Optional {@code predicate} filters both sides.
     *
     * <p><b>Uniqueness:</b> set semantics on {@code (srcId, predicate, dstId)} — same
     * as {@link #relationshipEdgesFromSrc}.</p>
     */
    public List<Reasoner.Edge> relationshipEdgesToDst(String realmId, DataDomain dataDomain,
                                                      String dstId, String predicate) {
        Objects.requireNonNull(dstId, "dstId");
        realmId = normalizeRealmId(realmId);
        String pred = blankToNull(predicate);
        Set<String> nonEager = allNonEagerProviderIds();

        Map<String, Reasoner.Edge> unique = new LinkedHashMap<>();
        List<OntologyEdge> store = pred != null
                ? edgeRepo.findByDstAndP(dataDomain, dstId, pred)
                : edgeRepo.findByDst(dataDomain, dstId);
        for (OntologyEdge e : store) {
            if (isStaleNonEagerStoreRow(e, nonEager)) continue;
            putEdge(unique, toReasonerEdge(e));
        }

        for (ComputedEdgeProvider<?> provider : registry.getAllProviders()) {
            if (provider.getMaterializationMode() == MaterializationMode.EAGER) continue;
            if (pred != null && !pred.equals(provider.getPredicate())) continue;
            for (String srcId : enumerateSourceIds(realmId, dataDomain, provider, DEFAULT_INVERSE_SOURCE_SCAN_LIMIT)) {
                for (Reasoner.Edge e : edgesFor(realmId, dataDomain, provider, srcId)) {
                    if (dstId.equals(e.dstId())) putEdge(unique, e);
                }
            }
        }
        return new ArrayList<>(unique.values());
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

    /** Provider ids currently registered as LAZY/ONDEMAND for {@code predicate}. */
    private Set<String> nonEagerProviderIds(String predicate) {
        Set<String> ids = new HashSet<>();
        for (ComputedEdgeProvider<?> p : providersForPredicate(predicate)) {
            if (p.getMaterializationMode() != MaterializationMode.EAGER) {
                ids.add(p.getProviderId());
            }
        }
        return ids;
    }

    private Set<String> allNonEagerProviderIds() {
        Set<String> ids = new HashSet<>();
        for (ComputedEdgeProvider<?> p : registry.getAllProviders()) {
            if (p.getMaterializationMode() != MaterializationMode.EAGER) {
                ids.add(p.getProviderId());
            }
        }
        return ids;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static Reasoner.Edge toReasonerEdge(OntologyEdge e) {
        return new Reasoner.Edge(
                e.getSrc(), e.getSrcType(), e.getP(),
                e.getDst(), e.getDstType(),
                Boolean.TRUE.equals(e.isInferred()),
                Optional.empty());
    }

    /**
     * Edge identity for set-style accumulation: source + predicate + destination.
     * Null components are treated as empty strings so keys stay stable.
     * Public so REST/MCP callers can merge multi-predicate results without
     * reintroducing bag semantics.
     */
    public static String edgeKey(Reasoner.Edge e) {
        if (e == null) return "";
        return nullToEmpty(e.srcId()) + "|" + nullToEmpty(e.p()) + "|" + nullToEmpty(e.dstId());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void putEdge(Map<String, Reasoner.Edge> unique, Reasoner.Edge e) {
        if (e == null) return;
        unique.putIfAbsent(edgeKey(e), e);
    }

    /**
     * Store contribution for inverse queries: drop rows still attributed to a
     * provider that is no longer EAGER (stale after an EAGER→LAZY/ONDEMAND switch).
     */
    private Set<String> storeSrcIdsExcludingNonEager(DataDomain dataDomain, String predicate, String dstId) {
        Set<String> nonEager = nonEagerProviderIds(predicate);
        if (nonEager.isEmpty()) {
            return new LinkedHashSet<>(edgeRepo.srcIdsByDst(dataDomain, predicate, dstId));
        }
        List<OntologyEdge> rows = edgeRepo.findByDstAndP(dataDomain, dstId, predicate);
        Set<String> ids = new LinkedHashSet<>();
        for (OntologyEdge e : rows) {
            if (isStaleNonEagerStoreRow(e, nonEager)) continue;
            if (e.getSrc() != null) ids.add(e.getSrc());
        }
        return ids;
    }

    private Set<String> storeDstIdsExcludingNonEager(DataDomain dataDomain, String predicate, String sourceId) {
        Set<String> nonEager = nonEagerProviderIds(predicate);
        if (nonEager.isEmpty()) {
            return new LinkedHashSet<>(edgeRepo.dstIdsBySrc(dataDomain, predicate, sourceId));
        }
        // Prefer realm-aware overload when possible; DataDomain-only path resolves realm internally.
        List<OntologyEdge> rows = edgeRepo.findBySrcAndP(dataDomain, sourceId, predicate);
        Set<String> ids = new LinkedHashSet<>();
        for (OntologyEdge e : rows) {
            if (isStaleNonEagerStoreRow(e, nonEager)) continue;
            if (e.getDst() != null) ids.add(e.getDst());
        }
        return ids;
    }

    private static boolean isStaleNonEagerStoreRow(OntologyEdge e, Set<String> nonEagerProviderIds) {
        if (e == null || nonEagerProviderIds.isEmpty()) return false;
        String pid = extractProviderId(e.getProv());
        return pid != null && nonEagerProviderIds.contains(pid);
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
