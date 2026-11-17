package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.ComputedEdgeCache;
import com.e2eq.ontology.core.ComputedEdgeRegistry;
import com.e2eq.ontology.metrics.OntologyMetrics;
import com.e2eq.ontology.mongo.ComputedEdgeReaderInverseTest.LazyProvider;
import com.e2eq.ontology.mongo.ComputedEdgeReaderInverseTest.SingleInstance;
import com.e2eq.ontology.mongo.ComputedEdgeReaderInverseTest.SourceEntity;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Query {@code hasEdge} / {@code hasIncomingEdge} ids include LAZY computed edges that were never stored. */
class ComputedEdgeLookupTest {

    private DataDomain domain;
    private Map<String, SourceEntity> sources;
    private ComputedEdgeLookup lookup;

    @BeforeEach
    void setUp() {
        domain = new DataDomain("org", "0001", "tenant", 0, "owner");
        OntologyEdgeRepo edgeRepo = mock(OntologyEdgeRepo.class);
        when(edgeRepo.findByDstAndP(any(DataDomain.class), anyString(), anyString())).thenReturn(List.of());
        when(edgeRepo.findBySrcAndP(any(DataDomain.class), anyString(), anyString())).thenReturn(List.of());
        when(edgeRepo.findBySrcAndP(anyString(), any(DataDomain.class), anyString(), anyString())).thenReturn(List.of());

        ComputedEdgeRegistry registry = new ComputedEdgeRegistry();
        registry.register(new LazyProvider());
        sources = new LinkedHashMap<>();
        ComputedEdgeRecomputeHandler recompute = new ComputedEdgeRecomputeHandler();
        recompute.setSourceEntityLoader((realmId, dd, type, id) -> Optional.ofNullable(sources.get(id)));
        SourceEntityEnumerator enumerator = new SourceEntityEnumerator() {
            @Override public boolean supports(Class<?> entityType) { return SourceEntity.class.equals(entityType); }
            @Override public List<String> listIds(String realmId, DataDomain dataDomain, Class<?> entityType,
                                                  String afterId, int limit) {
                return afterId == null ? List.copyOf(sources.keySet()) : List.of();
            }
        };
        lookup = new ComputedEdgeLookup();
        lookup.reader = new ComputedEdgeReader(registry, recompute, edgeRepo, new ComputedEdgeCache(),
                new OntologyMetrics(), new SingleInstance<>(enumerator));
    }

    @Test
    void hasEdgeSeesLazyEdgesThatWereNeverStored() {
        sources.put("assoc-1", new SourceEntity("assoc-1", List.of("loc-A")));
        sources.put("assoc-2", new SourceEntity("assoc-2", List.of("loc-B")));

        assertEquals(Set.of("assoc-1"), lookup.srcIdsByDst(domain, "canSeeLocation", "loc-A", null));
        assertEquals(Set.of(), lookup.srcIdsByDst(domain, "canSeeLocation", "loc-Z", null));
    }

    @Test
    void hasIncomingEdgeSeesLazyEdgesThatWereNeverStored() {
        sources.put("assoc-1", new SourceEntity("assoc-1", List.of("loc-A", "loc-B")));

        assertEquals(Set.of("loc-A", "loc-B"), lookup.dstIdsBySrc(domain, "canSeeLocation", "assoc-1", null));
    }

    @Test
    void withoutAPrincipalTheRealmIsTheTenant() {
        assertEquals("my-tenant", ComputedEdgeLookup.realm(new DataDomain("org", "0001", "my.tenant", 0, "owner")));
    }
}
