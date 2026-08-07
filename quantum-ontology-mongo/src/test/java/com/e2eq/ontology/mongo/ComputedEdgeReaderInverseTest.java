package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.ComputedEdgeCache;
import com.e2eq.ontology.core.ComputedEdgeProvider;
import com.e2eq.ontology.core.ComputedEdgeRegistry;
import com.e2eq.ontology.core.MaterializationMode;
import com.e2eq.ontology.metrics.OntologyMetrics;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that destination-keyed and source-keyed reads honor LAZY/ONDEMAND
 * providers (the PR #49 review gap: inverse hasEdge paths must not miss
 * unmaterialized computed edges).
 */
public class ComputedEdgeReaderInverseTest {

    private DataDomain domain;
    private ComputedEdgeRegistry registry;
    private OntologyEdgeRepo edgeRepo;
    private ComputedEdgeReader reader;
    private Map<String, SourceEntity> sources;

    @BeforeEach
    void setUp() {
        domain = new DataDomain();
        domain.setOrgRefName("org");
        domain.setAccountNum("0001");
        domain.setTenantId("tenant");
        domain.setDataSegment(0);

        registry = new ComputedEdgeRegistry();
        edgeRepo = mock(OntologyEdgeRepo.class);
        when(edgeRepo.srcIdsByDst(any(DataDomain.class), anyString(), anyString())).thenReturn(Set.of());
        when(edgeRepo.srcIdsByDstIn(any(DataDomain.class), anyString(), any())).thenReturn(Set.of());
        when(edgeRepo.dstIdsBySrc(any(DataDomain.class), anyString(), anyString())).thenReturn(Set.of());
        when(edgeRepo.findBySrcAndP(any(DataDomain.class), anyString(), anyString())).thenReturn(List.of());
        when(edgeRepo.findBySrcAndP(anyString(), any(DataDomain.class), anyString(), anyString())).thenReturn(List.of());
        when(edgeRepo.findByDstAndP(any(DataDomain.class), anyString(), anyString())).thenReturn(List.of());

        sources = new ConcurrentHashMap<>();

        LazyProvider provider = new LazyProvider();
        registry.register(provider);

        ComputedEdgeRecomputeHandler recompute = new ComputedEdgeRecomputeHandler();
        recompute.setSourceEntityLoader((realmId, dd, type, id) -> Optional.ofNullable(sources.get(id)));

        SourceEntityEnumerator enumerator = new SourceEntityEnumerator() {
            @Override public boolean supports(Class<?> entityType) {
                return SourceEntity.class.equals(entityType);
            }
            @Override public List<String> listIds(String realmId, DataDomain dataDomain,
                                                  Class<?> entityType, String afterId, int limit) {
                List<String> ids = sources.keySet().stream().sorted().toList();
                int start = 0;
                if (afterId != null) {
                    int idx = ids.indexOf(afterId);
                    start = idx < 0 ? ids.size() : idx + 1;
                }
                if (start >= ids.size()) return List.of();
                return ids.subList(start, Math.min(ids.size(), start + limit));
            }
        };

        reader = new ComputedEdgeReader(
                registry,
                recompute,
                edgeRepo,
                new ComputedEdgeCache(),
                new OntologyMetrics(),
                new SingleInstance<>(enumerator));
    }

    @Test
    void srcIdsByDstIncludesLazyProviderEdgesNotInStore() {
        sources.put("assoc-1", new SourceEntity("assoc-1", List.of("loc-A", "loc-B")));
        sources.put("assoc-2", new SourceEntity("assoc-2", List.of("loc-B")));

        Set<String> forA = reader.srcIdsByDst("realm", domain, "canSeeLocation", "loc-A");
        Set<String> forB = reader.srcIdsByDst("realm", domain, "canSeeLocation", "loc-B");
        Set<String> forZ = reader.srcIdsByDst("realm", domain, "canSeeLocation", "loc-Z");

        assertEquals(Set.of("assoc-1"), forA);
        assertEquals(Set.of("assoc-1", "assoc-2"), forB);
        assertTrue(forZ.isEmpty());
    }

    @Test
    void dstIdsBySrcIncludesLazyProviderEdges() {
        sources.put("assoc-1", new SourceEntity("assoc-1", List.of("loc-A", "loc-B")));

        Set<String> dsts = reader.dstIdsBySrc("realm", domain, "canSeeLocation", "assoc-1");
        assertEquals(Set.of("loc-A", "loc-B"), dsts);
    }

    @Test
    void srcIdsByDstUnionsStoreEagerRowsWithLazy() {
        sources.put("assoc-lazy", new SourceEntity("assoc-lazy", List.of("loc-X")));
        // Store row without non-EAGER provider provenance (explicit or other EAGER provider)
        OntologyEdge storeRow = edge("assoc-store", "canSeeLocation", "loc-X", null);
        when(edgeRepo.findByDstAndP(eq(domain), eq("loc-X"), eq("canSeeLocation")))
                .thenReturn(List.of(storeRow));

        Set<String> ids = reader.srcIdsByDst("realm", domain, "canSeeLocation", "loc-X");
        assertEquals(Set.of("assoc-lazy", "assoc-store"), ids);
    }

    @Test
    void srcIdsByDstDropsStaleRowsFromNonEagerProviders() {
        // Provider now LAZY and only grants loc-A; store still has stale loc-Y from when it was EAGER.
        sources.put("assoc-1", new SourceEntity("assoc-1", List.of("loc-A")));
        OntologyEdge stale = edge("assoc-1", "canSeeLocation", "loc-Y",
                Map.of("providerId", "LazyProvider"));
        when(edgeRepo.findByDstAndP(eq(domain), eq("loc-Y"), eq("canSeeLocation")))
                .thenReturn(List.of(stale));
        when(edgeRepo.srcIdsByDst(eq(domain), eq("canSeeLocation"), eq("loc-Y")))
                .thenReturn(Set.of("assoc-1"));

        Set<String> forStale = reader.srcIdsByDst("realm", domain, "canSeeLocation", "loc-Y");
        assertTrue(forStale.isEmpty(), "stale non-EAGER store row must not grant access");

        Set<String> forFresh = reader.srcIdsByDst("realm", domain, "canSeeLocation", "loc-A");
        assertEquals(Set.of("assoc-1"), forFresh);
    }

    @Test
    void normalizeRealmIdReplacesDotsLikeOntologyEdgeRepo() {
        assertEquals("foo-bar-com", ComputedEdgeReader.normalizeRealmId("foo.bar.com"));
        assertEquals("already-hyphen", ComputedEdgeReader.normalizeRealmId("already-hyphen"));
        assertNull(ComputedEdgeReader.normalizeRealmId(null));
    }

    private static OntologyEdge edge(String src, String p, String dst, Map<String, Object> prov) {
        OntologyEdge e = new OntologyEdge();
        e.setSrc(src);
        e.setP(p);
        e.setDst(dst);
        e.setProv(prov);
        e.setDerived(prov != null);
        return e;
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    static final class SourceEntity {
        final String id;
        final List<String> locations;
        SourceEntity(String id, List<String> locations) {
            this.id = id;
            this.locations = locations;
        }
        public String getId() { return id; }
    }

    static final class LazyProvider extends ComputedEdgeProvider<SourceEntity> {
        @Override public MaterializationMode getMaterializationMode() { return MaterializationMode.LAZY; }
        @Override public Class<SourceEntity> getSourceType() { return SourceEntity.class; }
        @Override public String getPredicate() { return "canSeeLocation"; }
        @Override public String getTargetTypeName() { return "Location"; }
        @Override
        protected Set<ComputedTarget> computeTargets(ComputationContext context, SourceEntity source) {
            Set<ComputedTarget> out = new LinkedHashSet<>();
            for (String loc : source.locations) out.add(new ComputedTarget(loc));
            return out;
        }
    }

    /** Single-element CDI Instance for tests. */
    static final class SingleInstance<T> implements Instance<T> {
        private final T value;
        SingleInstance(T value) { this.value = value; }
        @Override public Instance<T> select(Annotation... qualifiers) { return this; }
        @Override public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            @SuppressWarnings("unchecked") Instance<U> self = (Instance<U>) this;
            return self;
        }
        @Override public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            @SuppressWarnings("unchecked") Instance<U> self = (Instance<U>) this;
            return self;
        }
        @Override public boolean isUnsatisfied() { return value == null; }
        @Override public boolean isAmbiguous() { return false; }
        @Override public void destroy(T instance) {}
        @Override public Handle<T> getHandle() { throw new UnsupportedOperationException(); }
        @Override public Iterable<? extends Handle<T>> handles() { return List.of(); }
        @Override public T get() { return value; }
        @Override public Iterator<T> iterator() { return Stream.of(value).iterator(); }
    }
}
