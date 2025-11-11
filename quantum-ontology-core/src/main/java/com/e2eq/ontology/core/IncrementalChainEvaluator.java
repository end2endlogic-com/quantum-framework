package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry;

import java.util.*;

/**
 * Incremental evaluator for property-chain rules using the persisted EdgeStore as the graph.
 *
 * Given a tenantId, an anchor entityId X, and the set of changed base predicates, it evaluates only
 * the property chains [p1, ..., pn] => q that touch the local neighborhood of X and returns derived
 * q(X, Z) edges. It performs minimal queries by caching (node, predicate) lookups using EdgeStore
 * listOutgoingBy. The result de-duplicates q(X, Z) and honors functional/transitive traits:
 *
 * - functional(q): If q is functional, at most one q(X, Z) is returned. If an explicit q(X, Z0) already
 *   exists in the store, it is preferred and any newly implied candidates are dropped. Otherwise, a
 *   deterministic choice is made among candidates (lexicographically-smallest Z).
 * - transitive(q): If q is transitive, after deriving new q(X, Z1) edges from chains, a local closure
 *   is computed anchored at X by following existing q edges (X -> Z) and exploring Z -> ... via q only
 *   within the neighborhood reached by cached queries. No global fixpoint is attempted beyond nodes
 *   discovered from X in this evaluation.
 *
 * Conflict policy outline:
 * - If multiple chains imply different Z for a functional q, we keep the existing explicit q first; else
 *   pick the smallest Z. We do not delete pre-existing explicit q here; the materializer should apply
 *   pruning policies as needed around upserts.
 */
public final class IncrementalChainEvaluator {

    public static class Result {
        private final List<EdgeRecord> derived;
        private final int cacheHits;
        private final int cacheMisses;
        public Result(List<EdgeRecord> derived, int cacheHits, int cacheMisses) {
            this.derived = derived;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
        }
        public List<EdgeRecord> derivedEdges() { return derived; }
        public int cacheHits() { return cacheHits; }
        public int cacheMisses() { return cacheMisses; }
    }

    /**
     * Evaluate property chains around an anchor entityId (as X) and produce derived q(X, Z) edges.
     * Only chains whose predicates intersect changedPredicates are processed.
     */
    public Result evaluate(String tenantId,
                           String entityId,
                           Set<String> changedPredicates,
                           OntologyRegistry registry,
                           EdgeStore store) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(store, "store");
        if (changedPredicates == null) changedPredicates = Set.of();

        // Cache of (node, p) -> dsts to avoid repeated queries; LRU to cap memory
        LruCache<NodePred, Set<String>> outCache = new LruCache<>(512);

        // Collect all candidate q(Z) for this X, along with the implied property name
        Map<String, Set<String>> qToZs = new HashMap<>();

        List<PropertyChainDef> chains = registry.propertyChains();
        Set<String> activePredicates = new HashSet<>(changedPredicates);
        if (chains != null) {
            for (PropertyChainDef ch : chains) {
                List<String> seq = ch.chain();
                if (seq == null || seq.size() < 2) continue;
                if (!activePredicates.isEmpty() && Collections.disjoint(new HashSet<>(seq), activePredicates)) {
                    // None of the predicates (including newly produced) changed; skip for this pass
                    continue;
                }
                String q = ch.implies();
                // Multi-hop join starting at X = entityId
                Set<String> frontier = Set.of(entityId);
                final int MAX_TRANSITIVE_VISITS = 10000;
                for (String p : seq) {
                    Set<String> next = new HashSet<>();
                    boolean isTransitive = registry.propertyOf(p).map(PropertyDef::transitive).orElse(false);
                    if (isTransitive) {
                        // Expand closure over p starting from all nodes in current frontier
                        Deque<String> dq = new ArrayDeque<>(frontier);
                        Set<String> visited = new HashSet<>(frontier);
                        int visits = 0;
                        while (!dq.isEmpty() && visits < MAX_TRANSITIVE_VISITS) {
                            String node = dq.removeFirst();
                            visits++;
                            Set<String> dsts = queryOutgoing(store, tenantId, node, p, outCache);
                            for (String y : dsts) {
                                if (visited.add(y)) dq.addLast(y);
                                next.add(y);
                            }
                        }
                    } else {
                        for (String node : frontier) {
                            Set<String> dsts = queryOutgoing(store, tenantId, node, p, outCache);
                            next.addAll(dsts);
                        }
                    }
                    if (next.isEmpty()) { frontier = Set.of(); break; }
                    frontier = next;
                }
                if (!frontier.isEmpty()) {
                    Set<String> zs = qToZs.computeIfAbsent(q, __ -> new HashSet<>());
                    for (String z : frontier) {
                        if (!z.equals(entityId)) {
                            zs.add(z);
                            // Make q(X,z) visible for subsequent chain evaluations in this run
                            NodePred key = new NodePred(entityId, q);
                            Set<String> curr = outCache.computeIfAbsent(key, __ -> new HashSet<>());
                            curr.add(z);
                        }
                    }
                    // Mark q as updated so downstream chains that depend on it will be considered
                    activePredicates.add(q);
                }
            }
        }

        // For each q, apply functional/transitive handling and produce EdgeRecords
        List<EdgeRecord> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> en : qToZs.entrySet()) {
            String q = en.getKey();
            Set<String> zs = new HashSet<>(en.getValue());
            PropertyDef qDef = registry.propertyOf(q).orElse(null);

            // If q is functional, prefer existing explicit value; otherwise choose deterministic one
            if (qDef != null && qDef.functional()) {
                // existing explicit q(X, ?)
                String preferred = findExistingExplicit(store, tenantId, entityId, q);
                if (preferred != null) {
                    zs.clear();
                    zs.add(preferred);
                } else if (!zs.isEmpty()) {
                    String chosen = zs.stream().min(String::compareTo).get();
                    zs.clear();
                    zs.add(chosen);
                }
            }

            // If q is transitive, compute a local closure from X following q using cached queries
            if (qDef != null && qDef.transitive()) {
                Deque<String> dq = new ArrayDeque<>(zs);
                Set<String> visited = new HashSet<>();
                while (!dq.isEmpty()) {
                    String mid = dq.removeFirst();
                    if (!visited.add(mid)) continue;
                    Set<String> cont = queryOutgoing(store, tenantId, mid, q, outCache);
                    for (String z2 : cont) {
                        if (!z2.equals(entityId) && zs.add(z2)) dq.addLast(z2);
                    }
                }
            }

            // De-dup and emit records
            for (String z : zs) {
                String srcType = qDef != null ? qDef.domain().orElse(null) : null;
                String dstType = qDef != null ? qDef.range().orElse(null) : null;
                EdgeRecord rec = new EdgeRecord(tenantId, srcType, entityId, q, dstType, z,
                        true, Map.of("derivedBy", "chain"), /*support*/ null, new Date());
                rec.setDerived(true);
                rec.setInferred(true);
                out.add(rec);
            }
        }

        // Deduplicate across properties as safety (q,X,Z unique)
        out = dedup(out);
        return new Result(out, outCache.hits(), outCache.misses());
    }

    private static List<EdgeRecord> dedup(List<EdgeRecord> list) {
        Map<String, EdgeRecord> uniq = new LinkedHashMap<>();
        for (EdgeRecord e : list) {
            String key = e.getTenantId() + "|" + e.getSrc() + "|" + e.getP() + "|" + e.getDst();
            uniq.putIfAbsent(key, e);
        }
        return new ArrayList<>(uniq.values());
    }

    private static String findExistingExplicit(EdgeStore store, String tenantId, String src, String q) {
        try {
            List<EdgeRecord> existing = store.listOutgoingBy(tenantId, src, q);
            if (existing == null) return null;
            // Prefer explicit over derived/inferred
            for (EdgeRecord e : existing) {
                if (!e.isInferred() && !e.isDerived()) return e.getDst();
            }
            // If no explicit, but some exist, do not prefer any; leave null to allow deterministic pick among new
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Set<String> queryOutgoing(EdgeStore store, String tenantId, String node, String p,
                                             Map<NodePred, Set<String>> cache) {
        NodePred key = new NodePred(node, p);
        Set<String> cached = cache.get(key);
        if (cached != null) return cached;
        List<EdgeRecord> rows = store.listOutgoingBy(tenantId, node, p);
        Set<String> dsts = new HashSet<>();
        if (rows != null) {
            for (EdgeRecord r : rows) dsts.add(r.getDst());
        }
        cache.put(key, dsts);
        return dsts;
    }

    private record NodePred(String node, String p) {}

    // Simple LRU cache with hit/miss tracking for (node,p) -> dsts
    private static final class LruCache<K,V> extends LinkedHashMap<K,V> {
        private final int maxSize;
        private int hits;
        private int misses;
        LruCache(int maxSize) {
            super(16, 0.75f, true);
            this.maxSize = Math.max(1, maxSize);
        }
        @Override
        public V get(Object key) {
            V v = super.get(key);
            if (v != null) hits++; else misses++;
            return v;
        }
        int hits() { return hits; }
        int misses() { return misses; }
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<K,V> eldest) {
            return size() > maxSize;
        }
    }
}
