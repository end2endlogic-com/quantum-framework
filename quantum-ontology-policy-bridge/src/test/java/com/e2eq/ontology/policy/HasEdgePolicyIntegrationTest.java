package com.e2eq.ontology.policy;

import com.e2eq.ontology.mongo.EdgeDao;
import com.mongodb.client.model.Filters;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures policy-level hasEdge semantics using ListQueryRewriter:
 * - Tenant scoping: only edges for the provided tenantId are considered
 * - Composition: base filter is preserved when rewriting
 * - Empty semantics: when no matching edges exist, the rewritten filter yields no results
 */
public class HasEdgePolicyIntegrationTest {

    static class TenantAwareEdgeDao extends EdgeDao {
        private final Map<String, Set<String>> map = new HashMap<>(); // key: tenant|p|dst -> src ids
        public TenantAwareEdgeDao() { super(null); }
        public void put(String tenant, String p, String dst, String... srcs) {
            map.computeIfAbsent(key(tenant, p, dst), k -> new HashSet<>()).addAll(Arrays.asList(srcs));
        }
        private static String key(String tenant, String p, String dst){ return tenant+"|"+p+"|"+dst; }
        @Override
        public Set<String> srcIdsByDst(String tenantId, String p, String dst){
            return new HashSet<>(map.getOrDefault(key(tenantId, p, dst), Set.of()));
        }
        @Override
        public Set<String> srcIdsByDstIn(String tenantId, String p, Collection<String> dstIds){
            Set<String> rc = new HashSet<>();
            for (String d : dstIds) rc.addAll(map.getOrDefault(key(tenantId, p, d), Set.of()));
            return rc;
        }
    }

    @Test
    public void testHasEdgeTenantScopingAndComposition() {
        TenantAwareEdgeDao dao = new TenantAwareEdgeDao();
        // Same predicate/dst across tenants -> different src sets
        dao.put("t1", "placedInOrg", "OrgP", "O1", "O2");
        dao.put("t2", "placedInOrg", "OrgP", "X1");

        ListQueryRewriter rw = new ListQueryRewriter(dao);
        Bson base = Filters.eq("status", "OPEN");

        // For tenant t1 we should only include O1,O2 (not X1)
        Bson rewrittenT1 = rw.rewriteForHasEdge(base, "t1", "placedInOrg", "OrgP");
        String s1 = rewrittenT1.toString();
        assertTrue(s1.contains("status"), "base filter must be preserved");
        assertTrue(s1.contains("O1") && s1.contains("O2"), "t1 src IDs should be included");
        assertFalse(s1.contains("X1"), "t2 src IDs must not leak into t1");

        // For tenant t2 we should only include X1
        Bson rewrittenT2 = rw.rewriteForHasEdge(Filters.empty(), "t2", "placedInOrg", "OrgP");
        String s2 = rewrittenT2.toString();
        assertTrue(s2.contains("X1"), "t2 src IDs should be included");
        assertFalse(s2.contains("O1") || s2.contains("O2"), "t1 src IDs must not appear for t2");
    }

    @Test
    public void testHasEdgeEmptyYieldsNoResults() {
        TenantAwareEdgeDao dao = new TenantAwareEdgeDao();
        // No entries for this dst -> should force empty result set
        ListQueryRewriter rw = new ListQueryRewriter(dao);
        Bson rewritten = rw.rewriteForHasEdge(Filters.empty(), "t1", "orderShipsToRegion", "West");
        String s = rewritten.toString();
        // The bridge uses an impossible _id value to force no results
        assertTrue(s.contains("_id") && s.contains("__none__"), "empty hasEdge should force no results");
    }
}
