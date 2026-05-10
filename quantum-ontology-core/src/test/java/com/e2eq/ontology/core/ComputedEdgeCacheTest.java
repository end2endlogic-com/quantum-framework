package com.e2eq.ontology.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ComputedEdgeCacheTest {

    private ComputedEdgeCache.Key key(String src) {
        return new ComputedEdgeCache.Key("P1", "realm", "org", "acct", "tenant", 0, src);
    }

    private List<Reasoner.Edge> edges(String dst) {
        return List.of(new Reasoner.Edge("s1", "S", "p", dst, "T", false, Optional.empty()));
    }

    @Test
    void putThenGetReturnsTheStoredValue() {
        ComputedEdgeCache c = new ComputedEdgeCache();
        c.put(key("s1"), edges("a"), 0);
        Optional<List<Reasoner.Edge>> hit = c.get(key("s1"));
        assertTrue(hit.isPresent());
        assertEquals(1, hit.get().size());
        assertEquals(1L, c.stats().get("hits"));
    }

    @Test
    void unknownKeyMisses() {
        ComputedEdgeCache c = new ComputedEdgeCache();
        assertTrue(c.get(key("nope")).isEmpty());
        assertEquals(1L, c.stats().get("misses"));
    }

    @Test
    void zeroTtlMeansNoExpiration() {
        ComputedEdgeCache c = new ComputedEdgeCache();
        c.put(key("s1"), edges("a"), 0);
        assertTrue(c.get(key("s1")).isPresent());
    }

    @Test
    void invalidateRemovesEntry() {
        ComputedEdgeCache c = new ComputedEdgeCache();
        c.put(key("s1"), edges("a"), 0);
        c.invalidate(key("s1"));
        assertTrue(c.get(key("s1")).isEmpty());
        assertEquals(1L, c.stats().get("evictions"));
    }

    @Test
    void invalidateProviderRemovesAllForThatId() {
        ComputedEdgeCache c = new ComputedEdgeCache();
        c.put(key("s1"), edges("a"), 0);
        c.put(key("s2"), edges("b"), 0);
        c.put(new ComputedEdgeCache.Key("Other", "r", "o", "a", "t", 0, "s3"), edges("c"), 0);
        int n = c.invalidateProvider("P1");
        assertEquals(2, n);
        assertTrue(c.get(key("s1")).isEmpty());
        assertTrue(c.get(new ComputedEdgeCache.Key("Other", "r", "o", "a", "t", 0, "s3")).isPresent());
    }

    @Test
    void clearEmptiesAndCountsEvictions() {
        ComputedEdgeCache c = new ComputedEdgeCache();
        c.put(key("s1"), edges("a"), 0);
        c.put(key("s2"), edges("b"), 0);
        c.clear();
        assertEquals(0L, c.stats().get("size"));
        assertEquals(2L, c.stats().get("evictions"));
    }

    @Test
    void differingDataDomainFieldsDoNotCollide() {
        ComputedEdgeCache c = new ComputedEdgeCache();
        // Same provider/realm/tenant/source — different org and segment.
        ComputedEdgeCache.Key k1 = new ComputedEdgeCache.Key("P1", "r", "orgA", "1", "t", 0, "s1");
        ComputedEdgeCache.Key k2 = new ComputedEdgeCache.Key("P1", "r", "orgB", "1", "t", 0, "s1");
        ComputedEdgeCache.Key k3 = new ComputedEdgeCache.Key("P1", "r", "orgA", "1", "t", 1, "s1");

        c.put(k1, edges("a"), 0);
        assertTrue(c.get(k2).isEmpty(), "different orgRefName must not hit");
        assertTrue(c.get(k3).isEmpty(), "different dataSegment must not hit");
        assertTrue(c.get(k1).isPresent());
    }

    @Test
    void defaultMaterializationModeIsEager() {
        // Verifies the default contract from ComputedEdgeProvider.
        ComputedEdgeProvider<Object> p = new ComputedEdgeProvider<>() {
            @Override public Class<Object> getSourceType() { return Object.class; }
            @Override public String getPredicate() { return "p"; }
            @Override public String getTargetTypeName() { return "T"; }
            @Override protected java.util.Set<ComputedTarget> computeTargets(
                    ComputationContext c, Object s) { return java.util.Set.of(); }
        };
        assertEquals(MaterializationMode.EAGER, p.getMaterializationMode());
        assertEquals(0L, p.getCacheTtlSeconds());
    }
}
