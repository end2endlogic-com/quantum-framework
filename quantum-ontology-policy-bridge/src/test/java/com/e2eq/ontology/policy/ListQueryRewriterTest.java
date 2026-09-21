package com.e2eq.ontology.policy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
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
        private final Map<String, Set<String>> filteredMap = new HashMap<>(); // key: p|dst -> filtered src ids
        private final Map<String, Set<String>> incomingMap = new HashMap<>(); // key: p|src -> dst ids
        private final Map<String, Set<String>> filteredIncomingMap = new HashMap<>(); // key: p|src -> filtered dst ids

        public void put(String p, String dst, String... srcs) {
            map.computeIfAbsent(p + "|" + dst, k -> new HashSet<>()).addAll(Arrays.asList(srcs));
        }

        public void putFiltered(String p, String dst, String... srcs) {
            filteredMap.computeIfAbsent(p + "|" + dst, k -> new HashSet<>()).addAll(Arrays.asList(srcs));
        }

        public void putIncoming(String p, String src, String... dsts) {
            incomingMap.computeIfAbsent(p + "|" + src, k -> new HashSet<>()).addAll(Arrays.asList(dsts));
        }

        public void putIncomingFiltered(String p, String src, String... dsts) {
            filteredIncomingMap.computeIfAbsent(p + "|" + src, k -> new HashSet<>()).addAll(Arrays.asList(dsts));
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
        public Set<String> srcIdsByDst(DataDomain dataDomain, String p, String dst, Filter... edgeFilters) {
            if (edgeFilters != null && edgeFilters.length > 0 && edgeFilters[0] != null) {
                return new HashSet<>(filteredMap.getOrDefault(p + "|" + dst, Set.of()));
            }
            return new HashSet<>(map.getOrDefault(p + "|" + dst, Set.of()));
        }

        @Override
        public Set<String> srcIdsByDst(DataDomain dataDomain, String p, String dst) {
            return srcIdsByDst(dataDomain, p, dst, (Filter[]) null);
        }

        @Override
        public Set<String> srcIdsByDstIn(DataDomain dataDomain, String p, Collection<String> dstIds, Filter... edgeFilters) {
            Set<String> rc = new HashSet<>();
            for (String d : dstIds) {
                if (edgeFilters != null && edgeFilters.length > 0 && edgeFilters[0] != null) {
                    rc.addAll(filteredMap.getOrDefault(p + "|" + d, Set.of()));
                } else {
                    rc.addAll(map.getOrDefault(p + "|" + d, Set.of()));
                }
            }
            return rc;
        }

        @Override
        public Set<String> srcIdsByDstIn(DataDomain dataDomain, String p, Collection<String> dstIds) {
            return srcIdsByDstIn(dataDomain, p, dstIds, (Filter[]) null);
        }

        @Override
        public Set<String> dstIdsBySrc(DataDomain dataDomain, String p, String src, Filter... edgeFilters) {
            if (edgeFilters != null && edgeFilters.length > 0 && edgeFilters[0] != null) {
                return new HashSet<>(filteredIncomingMap.getOrDefault(p + "|" + src, Set.of()));
            }
            return new HashSet<>(incomingMap.getOrDefault(p + "|" + src, Set.of()));
        }

        @Override
        public Set<String> dstIdsBySrc(DataDomain dataDomain, String p, String src) {
            return dstIdsBySrc(dataDomain, p, src, (Filter[]) null);
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
        // Business-key src ids (O1/O2 are not ObjectId-shaped) match the refName field;
        // ObjectId-shaped ids would match _id. Accept either (see idsFilter).
        assertTrue(s.contains("refName") || s.contains("_id"));
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
        // notHasEdge negates the id/refName match with $nor (see ListQueryRewriter.notHasEdge).
        assertTrue(s2.toLowerCase().contains("nor"));
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

    @Test
    public void testHasEdgeWithEdgeFilter() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.put("placedInOrg", "OrgP", "O1", "O2");
        repo.putFiltered("placedInOrg", "OrgP", "O1"); // Only O1 has props.role == PRIMARY
        ListQueryRewriter rw = new ListQueryRewriter(repo);

        Filter f = rw.hasEdge(testDataDomain, "placedInOrg", "OrgP", Filters.eq("props.role", "PRIMARY"));
        String s = String.valueOf(f);
        assertTrue(s.contains("O1"), "O1 has role PRIMARY");
        assertFalse(s.contains("O2"), "O2 should be excluded by edge filter");

        // Verify fallback to unfiltered
        Filter fAll = rw.hasEdge(testDataDomain, "placedInOrg", "OrgP");
        String sAll = String.valueOf(fAll);
        assertTrue(sAll.contains("O1") && sAll.contains("O2"));
    }

    @Test
    public void testHasIncomingEdgeWithEdgeFilter() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.putIncoming("canSeeLocation", "Assoc1", "LocA", "LocB");
        repo.putIncomingFiltered("canSeeLocation", "Assoc1", "LocA"); // Only LocA is ACTIVE
        ListQueryRewriter rw = new ListQueryRewriter(repo);

        Filter f = rw.hasIncomingEdge(testDataDomain, "canSeeLocation", "Assoc1", Filters.eq("props.status", "ACTIVE"));
        String s = String.valueOf(f);
        assertTrue(s.contains("LocA"), "LocA is ACTIVE");
        assertFalse(s.contains("LocB"), "LocB should be excluded by edge filter");

        Filter fAll = rw.hasIncomingEdge(testDataDomain, "canSeeLocation", "Assoc1");
        String sAll = String.valueOf(fAll);
        assertTrue(sAll.contains("LocA") && sAll.contains("LocB"));
    }

    @Test
    public void testHasEdgeAnyWithEdgeFilter() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.put("orderShipsToRegion", "West", "O1");
        repo.put("orderShipsToRegion", "East", "O2", "O3");
        repo.putFiltered("orderShipsToRegion", "West", "O1");
        repo.putFiltered("orderShipsToRegion", "East", "O2"); // O3 excluded
        ListQueryRewriter rw = new ListQueryRewriter(repo);

        Filter f = rw.hasEdgeAny(testDataDomain, "orderShipsToRegion", List.of("West", "East"), Filters.eq("props.priority", 1));
        String s = String.valueOf(f);
        assertTrue(s.contains("O1") && s.contains("O2"));
        assertFalse(s.contains("O3"));
    }

    @Test
    public void testHasEdgeAllWithEdgeFilter() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.put("placedInOrg", "OrgA", "O1", "O2");
        repo.put("placedInOrg", "OrgB", "O1", "O3");
        repo.putFiltered("placedInOrg", "OrgA", "O1");
        repo.putFiltered("placedInOrg", "OrgB", "O1");
        ListQueryRewriter rw = new ListQueryRewriter(repo);

        Filter f = rw.hasEdgeAll(testDataDomain, "placedInOrg", List.of("OrgA", "OrgB"), Filters.eq("props.tier", "TOP"));
        String s = String.valueOf(f);
        assertTrue(s.contains("O1"));
        assertFalse(s.contains("O2"));
        assertFalse(s.contains("O3"));
    }

    @Test
    public void testIdsForHasEdgeWithEdgeFilter() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.put("placedInOrg", "OrgP", "O1", "O2");
        repo.putFiltered("placedInOrg", "OrgP", "O1");
        ListQueryRewriter rw = new ListQueryRewriter(repo);

        Set<String> ids = rw.idsForHasEdge(testDataDomain, "placedInOrg", "OrgP", Filters.eq("props.role", "PRIMARY"));
        assertEquals(Set.of("O1"), ids);

        Set<String> allIds = rw.idsForHasEdge(testDataDomain, "placedInOrg", "OrgP");
        assertEquals(Set.of("O1", "O2"), allIds);
    }

    @Test
    public void testIdsForHasEdgeAnyWithEdgeFilter() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.put("placedInOrg", "OrgA", "O1");
        repo.put("placedInOrg", "OrgB", "O2", "O3");
        repo.putFiltered("placedInOrg", "OrgA", "O1");
        repo.putFiltered("placedInOrg", "OrgB", "O2");
        ListQueryRewriter rw = new ListQueryRewriter(repo);

        Set<String> ids = rw.idsForHasEdgeAny(testDataDomain, "placedInOrg", List.of("OrgA", "OrgB"), Filters.eq("props.role", "PRIMARY"));
        assertEquals(Set.of("O1", "O2"), ids);
    }

    @Test
    public void testRewriteForHasEdgeWithEdgeFilter() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.put("placedInOrg", "OrgP", "O1", "O2");
        repo.putFiltered("placedInOrg", "OrgP", "O1");
        ListQueryRewriter rw = new ListQueryRewriter(repo);

        org.bson.conversions.Bson base = com.mongodb.client.model.Filters.eq("active", true);
        org.bson.conversions.Bson rewritten = rw.rewriteForHasEdge(base, "test-tenant", "placedInOrg", "OrgP", Filters.eq("props.role", "PRIMARY"));
        String s = rewritten.toBsonDocument().toJson();
        assertTrue(s.contains("O1"));
        assertFalse(s.contains("O2"));
    }

    @Test
    public void testRewriteForHasIncomingEdgeWithEdgeFilter() {
        FakeEdgeRepo repo = new FakeEdgeRepo();
        repo.putIncoming("canSeeLocation", "Assoc1", "LocA", "LocB");
        repo.putIncomingFiltered("canSeeLocation", "Assoc1", "LocA");
        ListQueryRewriter rw = new ListQueryRewriter(repo);

        org.bson.conversions.Bson base = com.mongodb.client.model.Filters.eq("active", true);
        org.bson.conversions.Bson rewritten = rw.rewriteForHasIncomingEdge(base, "test-tenant", "canSeeLocation", "Assoc1", Filters.eq("props.status", "ACTIVE"));
        String s = rewritten.toBsonDocument().toJson();
        assertTrue(s.contains("LocA"));
        assertFalse(s.contains("LocB"));
    }
}
