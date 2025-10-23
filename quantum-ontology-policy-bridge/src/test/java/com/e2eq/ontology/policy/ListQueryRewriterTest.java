package com.e2eq.ontology.policy;

import com.e2eq.ontology.mongo.EdgeRelationStore;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ListQueryRewriterTest {

    static class FakeEdgeDao implements EdgeRelationStore {
        private final Map<String, Set<String>> map = new HashMap<>(); // key: p|dst -> src ids
        public void put(String p, String dst, String... srcs) {
            map.computeIfAbsent(p+"|"+dst, k -> new HashSet<>()).addAll(Arrays.asList(srcs));
        }
        @Override public void upsert(String tenantId, String src, String p, String dst, boolean inferred, Map<String, Object> prov) { }
        @Override public void upsertMany(Collection<?> edgesOrDocs) { }
        @Override public void deleteBySrc(String tenantId, String src, boolean inferredOnly) { }
        @Override public void deleteBySrcAndPredicate(String tenantId, String src, String p) { }
        @Override public void deleteInferredBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep) { }
        @Override public Set<String> srcIdsByDst(String tenantId, String p, String dst){
            return new HashSet<>(map.getOrDefault(p+"|"+dst, Set.of()));
        }
        @Override public Set<String> srcIdsByDstIn(String tenantId, String p, Collection<String> dstIds){
            Set<String> rc = new HashSet<>();
            for (String d : dstIds) rc.addAll(map.getOrDefault(p+"|"+d, Set.of()));
            return rc;
        }
        @Override public Map<String, Set<String>> srcIdsByDstGrouped(String tenantId, String p, Collection<String> dstIds) { return Map.of(); }
        @Override public List<?> findBySrc(String tenantId, String src) { return List.of(); }
    }

    @Test
    public void testHasEdge() {
        FakeEdgeDao dao = new FakeEdgeDao();
        dao.put("placedInOrg", "OrgP", "O1", "O2");
        ListQueryRewriter rw = new ListQueryRewriter(dao);
        Filter f = rw.hasEdge("t1", "placedInOrg", "OrgP");
        String s = String.valueOf(f);
        assertTrue(s.contains("_id"));
        assertTrue(s.contains("O1") && s.contains("O2"));
    }

    @Test
    public void testHasEdgeAnyAndNot() {
        FakeEdgeDao dao = new FakeEdgeDao();
        dao.put("orderShipsToRegion", "West", "O1");
        dao.put("orderShipsToRegion", "East", "O2");
        ListQueryRewriter rw = new ListQueryRewriter(dao);
        Filter rAny = rw.hasEdgeAny("t1", "orderShipsToRegion", List.of("West", "East"));
        String s1 = String.valueOf(rAny);
        assertTrue(s1.contains("O1") && s1.contains("O2"));

        Filter rNot = rw.notHasEdge("t1", "orderShipsToRegion", "West");
        String s2 = String.valueOf(rNot);
        assertTrue(s2.toLowerCase().contains("nin"));
        assertTrue(s2.contains("O1"));
    }

    @Test
    public void testHasEdgeAllIntersectionSinglePredicate() {
        FakeEdgeDao dao = new FakeEdgeDao();
        // O1 appears in both dsts; O2 only in East
        dao.put("orderShipsToRegion", "West", "O1");
        dao.put("orderShipsToRegion", "East", "O1", "O2");
        ListQueryRewriter rw = new ListQueryRewriter(dao);
        Filter rAll = rw.hasEdgeAll("t1", "orderShipsToRegion", List.of("West", "East"));
        String s = String.valueOf(rAll);
        assertTrue(s.contains("O1"));
        assertFalse(s.contains("O2"));
    }
}
