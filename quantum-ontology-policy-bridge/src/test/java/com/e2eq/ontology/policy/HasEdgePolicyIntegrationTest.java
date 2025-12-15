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
 * Ensures policy-level hasEdge semantics using ListQueryRewriter:
 * - DataDomain scoping: only edges for the provided DataDomain are considered
 * - Composition: base filter is preserved when combined
 * - Empty semantics: when no matching edges exist, the filter yields no results
 */
public class HasEdgePolicyIntegrationTest {

    private DataDomain tenant1DataDomain;
    private DataDomain tenant2DataDomain;

    @BeforeEach
    void setUp() {
        tenant1DataDomain = new DataDomain();
        tenant1DataDomain.setOrgRefName("org-t1");
        tenant1DataDomain.setAccountNum("1111111111");
        tenant1DataDomain.setTenantId("t1");
        tenant1DataDomain.setOwnerId("system");
        tenant1DataDomain.setDataSegment(0);

        tenant2DataDomain = new DataDomain();
        tenant2DataDomain.setOrgRefName("org-t2");
        tenant2DataDomain.setAccountNum("2222222222");
        tenant2DataDomain.setTenantId("t2");
        tenant2DataDomain.setOwnerId("system");
        tenant2DataDomain.setDataSegment(0);
    }

    /**
     * Fake EdgeRepo for testing that ignores DataDomain scoping for simplicity.
     * Uses composite key that includes org to simulate DataDomain isolation.
     */
    static class TenantAwareEdgeRepo extends OntologyEdgeRepo {
        private final Map<String, Set<String>> map = new HashMap<>(); // key: org|p|dst -> src ids

        public void put(DataDomain dd, String p, String dst, String... srcs) {
            map.computeIfAbsent(key(dd, p, dst), k -> new HashSet<>()).addAll(Arrays.asList(srcs));
        }

        private static String key(DataDomain dd, String p, String dst) {
            return dd.getOrgRefName() + "|" + p + "|" + dst;
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
            return new HashSet<>(map.getOrDefault(key(dataDomain, p, dst), Set.of()));
        }

        @Override
        public Set<String> srcIdsByDstIn(DataDomain dataDomain, String p, Collection<String> dstIds) {
            Set<String> rc = new HashSet<>();
            for (String d : dstIds) rc.addAll(map.getOrDefault(key(dataDomain, p, d), Set.of()));
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
    public void testHasEdgeTenantScopingAndComposition() {
        TenantAwareEdgeRepo repo = new TenantAwareEdgeRepo();
        // Same predicate/dst across different DataDomains -> different src sets
        repo.put(tenant1DataDomain, "placedInOrg", "OrgP", "O1", "O2");
        repo.put(tenant2DataDomain, "placedInOrg", "OrgP", "X1");

        ListQueryRewriter rw = new ListQueryRewriter(repo);
        Filter base = Filters.eq("status", "OPEN");

        // For tenant1 we should only include O1,O2 (not X1)
        Filter combinedT1 = Filters.and(base, rw.hasEdge(tenant1DataDomain, "placedInOrg", "OrgP"));
        String s1 = String.valueOf(combinedT1);
        assertTrue(s1.contains("status"), "base filter must be preserved");
        assertTrue(s1.contains("O1") && s1.contains("O2"), "t1 src IDs should be included");
        assertFalse(s1.contains("X1"), "t2 src IDs must not leak into t1");

        // For tenant2 we should only include X1
        Filter onlyHasEdgeT2 = rw.hasEdge(tenant2DataDomain, "placedInOrg", "OrgP");
        String s2 = String.valueOf(onlyHasEdgeT2);
        assertTrue(s2.contains("X1"), "t2 src IDs should be included");
        assertFalse(s2.contains("O1") || s2.contains("O2"), "t1 src IDs must not appear for t2");
    }

    @Test
    public void testHasEdgeEmptyYieldsNoResults() {
        TenantAwareEdgeRepo repo = new TenantAwareEdgeRepo();
        // No entries for this dst -> should force empty result set
        ListQueryRewriter rw = new ListQueryRewriter(repo);
        Filter f = rw.hasEdge(tenant1DataDomain, "orderShipsToRegion", "West");
        String s = String.valueOf(f);
        // The bridge uses an impossible _id value to force no results
        assertTrue(s.contains("_id") && s.contains("__none__"), "empty hasEdge should force no results");
    }
}
