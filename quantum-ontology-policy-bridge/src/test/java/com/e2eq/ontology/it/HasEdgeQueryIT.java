package com.e2eq.ontology.it;

import com.e2eq.ontology.core.ForwardChainingReasoner;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.policy.ListQueryRewriter;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating hasEdge operator usage in getListByQuery methods
 */
@QuarkusTest
public class HasEdgeQueryIT {

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

    @BeforeEach
    public void setup() {
        reasoner = new ForwardChainingReasoner();
        edgeRepo.deleteAll();

        // Clean orders collection
        datastore.getDatabase().getCollection("orders").drop();
    }

    @Test
    public void testHasEdge_SingleOrganization() {
        // Given: Orders in database with different statuses
        createOrder("ORDER-001", "OPEN");
        createOrder("ORDER-002", "OPEN");
        createOrder("ORDER-003", "CLOSED");
        createOrder("ORDER-004", "OPEN");

        // Setup ontology edges: ORDER-001 and ORDER-002 are in ORG-ACME
        setupOrderInOrg("ORDER-001", "CUST-A", "ORG-ACME");
        setupOrderInOrg("ORDER-002", "CUST-B", "ORG-ACME");
        setupOrderInOrg("ORDER-003", "CUST-C", "ORG-OTHER");
        setupOrderInOrg("ORDER-004", "CUST-D", "ORG-OTHER");

        // When: Query for OPEN orders in ORG-ACME using hasEdge
        Filter statusFilter = Filters.eq("status", "OPEN");
        Filter orgFilter = queryRewriter.hasEdge(TENANT, "placedInOrg", "ORG-ACME");
        Filter combinedFilter = Filters.and(statusFilter, orgFilter);

        List<TestOrder> results = datastore.find(TestOrder.class)
                .filter(combinedFilter)
                .iterator()
                .toList();

        // Then: Should only return OPEN orders from ORG-ACME
        assertEquals(2, results.size(), "Should find 2 OPEN orders in ORG-ACME");
        assertTrue(results.stream().anyMatch(o -> o.getRefName().equals("ORDER-001")));
        assertTrue(results.stream().anyMatch(o -> o.getRefName().equals("ORDER-002")));
        assertTrue(results.stream().allMatch(o -> o.getStatus().equals("OPEN")));
    }

    @Test
    public void testHasEdgeAny_MultipleOrganizations() {
        // Given: Orders across multiple organizations
        createOrder("ORDER-001", "OPEN");
        createOrder("ORDER-002", "OPEN");
        createOrder("ORDER-003", "OPEN");
        createOrder("ORDER-004", "OPEN");

        setupOrderInOrg("ORDER-001", "CUST-A", "ORG-ACME");
        setupOrderInOrg("ORDER-002", "CUST-B", "ORG-GLOBEX");
        setupOrderInOrg("ORDER-003", "CUST-C", "ORG-INITECH");
        setupOrderInOrg("ORDER-004", "CUST-D", "ORG-OTHER");

        // When: Query for orders in either ORG-ACME or ORG-GLOBEX
        Filter orgFilter = queryRewriter.hasEdgeAny(TENANT, "placedInOrg",
                List.of("ORG-ACME", "ORG-GLOBEX"));

        List<TestOrder> results = datastore.find(TestOrder.class)
                .filter(orgFilter)
                .iterator()
                .toList();

        // Then: Should return orders from both organizations
        assertEquals(2, results.size(), "Should find orders from 2 organizations");
        assertTrue(results.stream().anyMatch(o -> o.getRefName().equals("ORDER-001")));
        assertTrue(results.stream().anyMatch(o -> o.getRefName().equals("ORDER-002")));
    }

    @Test
    public void testNotHasEdge_ExcludeOrganization() {
        // Given: Orders in different organizations
        createOrder("ORDER-001", "OPEN");
        createOrder("ORDER-002", "OPEN");
        createOrder("ORDER-003", "OPEN");

        setupOrderInOrg("ORDER-001", "CUST-A", "ORG-RESTRICTED");
        setupOrderInOrg("ORDER-002", "CUST-B", "ORG-ALLOWED");
        setupOrderInOrg("ORDER-003", "CUST-C", "ORG-ALLOWED");

        // When: Query for orders NOT in restricted organization
        Filter notRestrictedFilter = queryRewriter.notHasEdge(TENANT, "placedInOrg", "ORG-RESTRICTED");

        List<TestOrder> results = datastore.find(TestOrder.class)
                .filter(notRestrictedFilter)
                .iterator()
                .toList();

        // Then: Should exclude orders from restricted org
        assertEquals(2, results.size(), "Should find 2 orders not in restricted org");
        assertFalse(results.stream().anyMatch(o -> o.getRefName().equals("ORDER-001")));
        assertTrue(results.stream().anyMatch(o -> o.getRefName().equals("ORDER-002")));
        assertTrue(results.stream().anyMatch(o -> o.getRefName().equals("ORDER-003")));
    }

    @Test
    public void testHasEdge_EmptyResult() {
        // Given: Orders exist but none in target organization
        createOrder("ORDER-001", "OPEN");
        setupOrderInOrg("ORDER-001", "CUST-A", "ORG-OTHER");

        // When: Query for orders in non-existent organization
        Filter orgFilter = queryRewriter.hasEdge(TENANT, "placedInOrg", "ORG-NONEXISTENT");

        List<TestOrder> results = datastore.find(TestOrder.class)
                .filter(orgFilter)
                .iterator()
                .toList();

        // Then: Should return empty result
        assertEquals(0, results.size(), "Should find no orders in non-existent org");
    }

    private void createOrder(String refName, String status) {
        TestOrder order = new TestOrder();
        order.setRefName(refName);
        order.setStatus(status);
        datastore.save(order);
    }

    private void setupOrderInOrg(String orderId, String customerId, String orgId) {
        // Store explicit edges
        edgeRepo.upsert(TENANT, orderId, "placedBy", customerId, false, null);
        edgeRepo.upsert(TENANT, customerId, "memberOf", orgId, false, null);

        // Infer placedInOrg edge
        List<Reasoner.Edge> explicitEdges = List.of(
                new Reasoner.Edge(orderId, "placedBy", customerId, false, Optional.empty()),
                new Reasoner.Edge(customerId, "memberOf", orgId, false, Optional.empty())
        );

        Reasoner.EntitySnapshot snapshot = new Reasoner.EntitySnapshot(TENANT, orderId, "Order", explicitEdges);
        Reasoner.InferenceResult result = reasoner.infer(snapshot, ontologyRegistry);

        for (Reasoner.Edge edge : result.addEdges()) {
            Map<String, Object> prov = edge.prov().map(p -> Map.<String, Object>of("rule", p)).orElse(null);
            edgeRepo.upsert(TENANT, edge.srcId(), edge.p(), edge.dstId(), edge.inferred(), prov);
        }
    }
}
