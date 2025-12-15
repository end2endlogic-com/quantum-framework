package com.e2eq.ontology.policy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ListQueryRewriter with DataDomain scoping.
 */
public class ListQueryRewriterTest {

    private DataDomain testDataDomain;

    @BeforeEach
    void setUp() {
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("test-org");
        testDataDomain.setAccountNum("1234567890");
        testDataDomain.setTenantId("test-tenant");
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);
    }

    /**
     * Fake EdgeRepo for testing that stores edges keyed by p|dst.
     * Ignores DataDomain filtering in this test fake since we only use one domain.
     */
    static class FakeEdgeRepo extends OntologyEdgeRepo {
        private final Map<String, Set<String>> map = new HashMap<>(); // key: p|dst -> src ids

        public void put(String p, String dst, String... srcs) {
            map.computeIfAbsent(p + "|" + dst, k -> new HashSet<>()).addAll(Arrays.asList(srcs));
        }

        @Override
        public void upsert(DataDomain dataDomain, String srcType, String src, String p, String dstType, String dst, boolean inferred, Map<String, Object> prov) { }

        @Override
        public void upsertMany(Collection<?> edgesOrDocs) { }

        @Override
        public void deleteBySrc(DataDomain dataDomain, String src, boolean inferredOnly) { }

        @Override
        public void deleteBySrcAndPredicate(DataDomain dataDomain, String src, String p) { }

        @Override
        public void deleteInferredBySrcNotIn(DataDomain dataDomain, String src, String p, Collection<String> dstKeep) { }

        @Override
        public Set<String> srcIdsByDst(DataDomain dataDomain, String p, String dst) {
            return new HashSet<>(map.getOrDefault(p + "|" + dst, Set.of()));
        }

        @Override
        public Set<String> srcIdsByDstIn(DataDomain dataDomain, String p, Collection<String> dstIds) {
            Set<String> rc = new HashSet<>();
            for (String d : dstIds) {
                rc.addAll(map.getOrDefault(p + "|" + d, Set.of()));
            }
            return rc;
        }

        @Override
        public Map<String, Set<String>> srcIdsByDstGrouped(DataDomain dataDomain, String p, Collection<String> dstIds) {
            return Map.of();
        }

        @Override
        public List<OntologyEdge> findBySrc(DataDomain dataDomain, String src) {
            return List.of();
        }
    }

    @Test
    public void testHasEdge() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.put("placedInOrg", "OrgP", "O1", "O2");
        ListQueryRewriter rw = new ListQueryRewriter(repo);
        
        Filter f = rw.hasEdge(testDataDomain, "placedInOrg", "OrgP");
        String s = String.valueOf(f);
        assertTrue(s.contains("_id"));
        assertTrue(s.contains("O1") && s.contains("O2"));
    }

    @Test
    public void testHasEdgeAnyAndNot() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.put("orderShipsToRegion", "West", "O1");
        repo.put("orderShipsToRegion", "East", "O2");
        ListQueryRewriter rw = new ListQueryRewriter(repo);
        
        Filter rAny = rw.hasEdgeAny(testDataDomain, "orderShipsToRegion", List.of("West", "East"));
        String s1 = String.valueOf(rAny);
        assertTrue(s1.contains("O1") && s1.contains("O2"));

        Filter rNot = rw.notHasEdge(testDataDomain, "orderShipsToRegion", "West");
        String s2 = String.valueOf(rNot);
        assertTrue(s2.toLowerCase().contains("nin"));
        assertTrue(s2.contains("O1"));
    }

    @Test
    public void testHasEdgeAllIntersectionSinglePredicate() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        // O1 appears in both dsts; O2 only in East
        repo.put("orderShipsToRegion", "West", "O1");
        repo.put("orderShipsToRegion", "East", "O1", "O2");
        ListQueryRewriter rw = new ListQueryRewriter(repo);
        
        Filter rAll = rw.hasEdgeAll(testDataDomain, "orderShipsToRegion", List.of("West", "East"));
        String s = String.valueOf(rAll);
        assertTrue(s.contains("O1"));
        assertFalse(s.contains("O2"));
    }

    @Test
    public void testHasEdgeEmpty() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        ListQueryRewriter rw = new ListQueryRewriter(repo);
        
        Filter f = rw.hasEdge(testDataDomain, "nonexistent", "any");
        String s = String.valueOf(f);
        // Should return a filter that matches nothing
        assertTrue(s.contains("__none__"));
    }

    @Test
    public void testHasEdgeAllMultiPredicates() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.put("placedInOrg", "OrgA", "O1", "O2");
        repo.put("placedInOrg", "OrgB", "O2", "O3");
        repo.put("shipsTo", "RegionX", "O2", "O3");
        ListQueryRewriter rw = new ListQueryRewriter(repo);

        Map<String, Collection<String>> predicateToDstIds = new HashMap<>();
        predicateToDstIds.put("placedInOrg", List.of("OrgA", "OrgB"));
        predicateToDstIds.put("shipsTo", List.of("RegionX"));

        Filter f = rw.hasEdgeAll(testDataDomain, predicateToDstIds);
        String s = String.valueOf(f);
        // Only O2 satisfies both predicates with ALL destinations
        assertTrue(s.contains("O2"));
    }
}
