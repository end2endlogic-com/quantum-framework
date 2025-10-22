package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OntologyMaterializerTest {

    static class CapturingEdgeDao implements EdgeRelationStore {
        List<Document> upserts = new ArrayList<>();
        List<String> deleteCalls = new ArrayList<>();
        List<Document> existing = new ArrayList<>();
        @Override public void upsert(String tenantId, String src, String p, String dst, boolean inferred, Map<String, Object> prov) { /* not used */ }
        @Override public void upsertMany(Collection<?> docs){
            for (Object o : docs) if (o instanceof Document d) upserts.add(d);
        }
        @Override public void deleteBySrc(String tenantId, String src, boolean inferredOnly) { /* not used */ }
        @Override public void deleteBySrcAndPredicate(String tenantId, String src, String p) { /* not used */ }
        @Override public void deleteInferredBySrcNotIn(String tenantId, String src, String p, Collection<String> dstKeep){
            deleteCalls.add(src+"|"+p+"|"+new TreeSet<>(dstKeep));
        }
        @Override public Set<String> srcIdsByDst(String tenantId, String p, String dst) { return Set.of(); }
        @Override public Set<String> srcIdsByDstIn(String tenantId, String p, Collection<String> dstIds) { return Set.of(); }
        @Override public Map<String, Set<String>> srcIdsByDstGrouped(String tenantId, String p, Collection<String> dstIds) { return Map.of(); }
        @Override public List<?> findBySrc(String tenantId, String src){ return new ArrayList<>(existing); }
    }

    private OntologyRegistry registry() {
        Map<String, OntologyRegistry.ClassDef> classes = Map.of(
                "Order", new OntologyRegistry.ClassDef("Order", Set.of(), Set.of(), Set.of()),
                "Customer", new OntologyRegistry.ClassDef("Customer", Set.of(), Set.of(), Set.of()),
                "Organization", new OntologyRegistry.ClassDef("Organization", Set.of(), Set.of(), Set.of())
        );
        Map<String, OntologyRegistry.PropertyDef> props = new HashMap<>();
        props.put("placedBy", new OntologyRegistry.PropertyDef("placedBy", Optional.of("Order"), Optional.of("Customer"), false, Optional.empty(), false));
        props.put("memberOf", new OntologyRegistry.PropertyDef("memberOf", Optional.of("Customer"), Optional.of("Organization"), false, Optional.empty(), false));
        props.put("placedInOrg", new OntologyRegistry.PropertyDef("placedInOrg", Optional.of("Order"), Optional.of("Organization"), false, Optional.empty(), false));
        List<OntologyRegistry.PropertyChainDef> chains = List.of(
                new OntologyRegistry.PropertyChainDef(List.of("placedBy", "memberOf"), "placedInOrg")
        );
        return OntologyRegistry.inMemory(new OntologyRegistry.TBox(classes, props, chains));
    }

    @Test
    public void testApplyUpsertsAndDeletes() {
        Reasoner reasoner = new ForwardChainingReasoner();
        OntologyRegistry reg = registry();
        CapturingEdgeDao dao = new CapturingEdgeDao();
        OntologyMaterializer mat = new OntologyMaterializer(reasoner, reg, dao);

        String tenant = "t1";
        String order = "O1";
        String cust = "C9";
        String orgA = "OrgA";

        // existing inferred edge that should be pruned (predicate placedInOrg to OrgOld)
        dao.existing.add(new Document("tenantId", tenant)
                .append("src", order).append("p", "placedInOrg").append("dst", "OrgOld")
                .append("inferred", true));

        List<Reasoner.Edge> explicit = List.of(
                new Reasoner.Edge(order, "placedBy", cust, false, Optional.empty()),
                new Reasoner.Edge(cust, "memberOf", orgA, false, Optional.empty())
        );

        mat.apply(tenant, order, "Order", explicit);

        // verify upsert for new inferred edge to OrgA
        boolean foundUpsert = dao.upserts.stream().anyMatch(d ->
                tenant.equals(d.getString("tenantId")) &&
                        order.equals(d.getString("src")) &&
                        "placedInOrg".equals(d.getString("p")) &&
                        orgA.equals(d.getString("dst")) &&
                        Boolean.TRUE.equals(d.getBoolean("inferred"))
        );
        assertTrue(foundUpsert, "should upsert placedInOrg to OrgA");

        // verify delete of obsolete inferred edge not in keep set
        boolean foundDelete = dao.deleteCalls.stream().anyMatch(s -> s.contains(order+"|placedInOrg|"));
        assertTrue(foundDelete, "should prune obsolete placedInOrg edges");
    }
}
