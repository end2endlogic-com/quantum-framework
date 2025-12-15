package com.e2eq.ontology.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.ForwardChainingReasoner;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.policy.ListQueryRewriter;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SymmetricAndSubPropertyIT {

    @Inject
    OntologyRegistry ontologyRegistry;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    ListQueryRewriter queryRewriter;

    @Inject
    MorphiaDatastore datastore;

    private static final String TENANT = "test-tenant";
    private ForwardChainingReasoner reasoner;
    private DataDomain testDataDomain;

    @BeforeEach
    public void setup() {
        reasoner = new ForwardChainingReasoner();
        edgeRepo.deleteAll();
        datastore.getDatabase().getCollection("orders").drop();
        
        // Create test DataDomain
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("test-org");
        testDataDomain.setAccountNum("1234567890");
        testDataDomain.setTenantId(TENANT);
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);
    }

    @Test
    public void testSubPropertyOf_materializesSuperProperty() {
        // Given: one OPEN order placed in ORG-ACME via chain
        createOrder("ORDER-101", "OPEN");
        setupOrderInOrg("ORDER-101", "CUST-X", "ORG-ACME");

        // When: query using super property inOrg (placedInOrg ⊑ inOrg)
        Filter f = Filters.and(Filters.eq("status", "OPEN"), queryRewriter.hasEdge(testDataDomain, "inOrg", "ORG-ACME"));
        List<TestOrder> results = datastore.find(TestOrder.class).filter(f).iterator().toList();

        // Then: order should be returned through super-property inference
        assertEquals(1, results.size());
        assertEquals("ORDER-101", results.get(0).getRefName());
    }

    @Test
    public void testSymmetric_propertyInference() {
        // Given: ORG-A peerOf ORG-B explicitly
        edgeRepo.upsert(testDataDomain, "Organization", "ORG-A", "peerOf", "Organization", "ORG-B", false, null);

        // Infer symmetric counterpart using reasoner on an org snapshot
        List<Reasoner.Edge> explicit = List.of(new Reasoner.Edge("ORG-A", "Organization", "peerOf", "ORG-B", "Organization", false, Optional.empty()));
        Reasoner.EntitySnapshot snap = new Reasoner.EntitySnapshot(TENANT, "ORG-A", "Organization", explicit);
        Reasoner.InferenceResult out = reasoner.infer(snap, ontologyRegistry);
        for (Reasoner.Edge e : out.addEdges()) {
            Map<String, Object> prov = e.prov().map(p -> Map.<String, Object>of("rule", p)).orElse(null);
            edgeRepo.upsert(testDataDomain, e.srcType(), e.srcId(), e.p(), e.dstType(), e.dstId(), e.inferred(), prov);
        }

        // When: querying for who is peerOf ORG-A should include ORG-B
        Set<String> srcs = edgeRepo.srcIdsByDst(testDataDomain, "peerOf", "ORG-A");
        assertTrue(srcs.contains("ORG-B"), "Symmetric edge should be present from ORG-B to ORG-A");
    }

    private void createOrder(String refName, String status) {
        TestOrder order = new TestOrder();
        order.setRefName(refName);
        order.setStatus(status);
        datastore.save(order);
    }

    private void setupOrderInOrg(String orderId, String customerId, String orgId) {
        edgeRepo.upsert(testDataDomain, "Order", orderId, "placedBy", "Customer", customerId, false, null);
        edgeRepo.upsert(testDataDomain, "Customer", customerId, "memberOf", "Organization", orgId, false, null);

        List<Reasoner.Edge> explicitEdges = List.of(
                new Reasoner.Edge(orderId, "Order", "placedBy", customerId, "Customer", false, Optional.empty()),
                new Reasoner.Edge(customerId, "Customer", "memberOf", orgId, "Organization", false, Optional.empty())
        );

        Reasoner.EntitySnapshot snapshot = new Reasoner.EntitySnapshot(TENANT, orderId, "Order", explicitEdges);
        Reasoner.InferenceResult result = reasoner.infer(snapshot, ontologyRegistry);

        for (Reasoner.Edge edge : result.addEdges()) {
            Map<String, Object> prov = edge.prov().map(p -> Map.<String, Object>of("rule", p)).orElse(null);
            edgeRepo.upsert(testDataDomain, edge.srcType(), edge.srcId(), edge.p(), edge.dstType(), edge.dstId(), edge.inferred(), prov);
        }
    }
}
