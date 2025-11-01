package com.e2eq.ontology.it;

import com.e2eq.ontology.core.ForwardChainingReasoner;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating:
 * 1. Semantic Reasoning: Using property chains to infer relationships
 * 2. Policy Enforcement: Using inferred edges to filter queries based on organizational hierarchy
 */
@QuarkusTest
public class OntologySemanticReasoningIT {

    @Inject
    OntologyRegistry ontologyRegistry;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    TestOrderRepo orderRepo;

    private static final String TENANT = "test-tenant";
    private ForwardChainingReasoner reasoner;

    @BeforeEach
    public void setup() {
        reasoner = new ForwardChainingReasoner();
        edgeRepo.deleteAll();
    }

    @Test
    public void testSemanticReasoning_PropertyChainInference() {
        // Given: An order placed by a customer who is member of an organization
        String orderId = "ORDER-001";
        String customerId = "CUST-123";
        String orgId = "ORG-ACME";

        List<Reasoner.Edge> explicitEdges = List.of(
                new Reasoner.Edge(orderId, "Order", "placedBy", customerId, "Customer", false, Optional.empty()),
                new Reasoner.Edge(customerId, "Customer", "memberOf", orgId, "Organization", false, Optional.empty())
        );

        // When: Forward chaining reasoner infers new edges
        Reasoner.EntitySnapshot snapshot = new Reasoner.EntitySnapshot(TENANT, orderId, "Order", explicitEdges);
        Reasoner.InferenceResult result = reasoner.infer(snapshot, ontologyRegistry);

        // Then: The reasoner should infer placedInOrg relationship via property chain
        boolean foundInferredEdge = result.addEdges().stream()
                .anyMatch(e -> e.srcId().equals(orderId)
                        && e.p().equals("placedInOrg")
                        && e.dstId().equals(orgId)
                        && e.inferred());

        assertTrue(foundInferredEdge, "Should infer placedInOrg from placedBy + memberOf chain");

        // Verify provenance is tracked
        result.addEdges().stream()
                .filter(e -> e.p().equals("placedInOrg"))
                .forEach(e -> assertTrue(e.prov().isPresent(), "Inferred edges should have provenance"));
    }

    @Test
    public void testPolicyEnforcement_OrganizationalHierarchy() {
        // Given: Orders from different customers in different organizations
        String order1 = "ORDER-001";
        String order2 = "ORDER-002";
        String order3 = "ORDER-003";

        String cust1 = "CUST-A";
        String cust2 = "CUST-B";
        String cust3 = "CUST-C";

        String orgAcme = "ORG-ACME";
        String orgOther = "ORG-OTHER";

        // Store explicit edges
        edgeRepo.upsert(TENANT, "Order", order1, "placedBy", "Customer", cust1, false, null);
        edgeRepo.upsert(TENANT, "Customer", cust1, "memberOf", "Organization", orgAcme, false, null);

        edgeRepo.upsert(TENANT, "Order", order2, "placedBy", "Customer", cust2, false, null);
        edgeRepo.upsert(TENANT, "Customer", cust2, "memberOf", "Organization", orgAcme, false, null);

        edgeRepo.upsert(TENANT, "Order", order3, "placedBy", "Customer", cust3, false, null);
        edgeRepo.upsert(TENANT, "Customer", cust3, "memberOf", "Organization", orgOther, false, null);

        // Infer placedInOrg edges for each order
        inferAndStoreEdges(order1, "Order", List.of(
                new Reasoner.Edge(order1, "Order", "placedBy", cust1, "Customer", false, Optional.empty()),
                new Reasoner.Edge(cust1, "Customer", "memberOf", orgAcme, "Organization", false, Optional.empty())
        ));

        inferAndStoreEdges(order2, "Order", List.of(
                new Reasoner.Edge(order2, "Order", "placedBy", cust2, "Customer", false, Optional.empty()),
                new Reasoner.Edge(cust2, "Customer", "memberOf", orgAcme, "Organization", false, Optional.empty())
        ));

        inferAndStoreEdges(order3, "Order", List.of(
                new Reasoner.Edge(order3, "Order", "placedBy", cust3, "Customer", false, Optional.empty()),
                new Reasoner.Edge(cust3, "Customer", "memberOf", orgOther, "Organization", false, Optional.empty())
        ));

        // When: Query for orders placed in ORG-ACME using ontology edges
        Set<String> orderIdsInAcme = edgeRepo.srcIdsByDst(TENANT, "placedInOrg", orgAcme);

        // Then: Should only return orders from ACME organization
        assertEquals(2, orderIdsInAcme.size(), "Should find 2 orders in ACME org");
        assertTrue(orderIdsInAcme.contains(order1), "Should include ORDER-001");
        assertTrue(orderIdsInAcme.contains(order2), "Should include ORDER-002");
        assertFalse(orderIdsInAcme.contains(order3), "Should NOT include ORDER-003 from OTHER org");
    }

    @Test
    public void testPolicyEnforcement_MultiTenantIsolation() {
        // Given: Same organization ID in different tenants
        String tenant1 = "tenant-1";
        String tenant2 = "tenant-2";
        String orderId = "ORDER-001";
        String customerId = "CUST-123";
        String orgId = "ORG-SHARED";

        // Store edges in tenant1
        edgeRepo.upsert(tenant1, "Order", orderId, "placedBy", "Customer", customerId, false, null);
        edgeRepo.upsert(tenant1, "Customer", customerId, "memberOf", "Organization", orgId, false, null);

        inferAndStoreEdges(tenant1, orderId, "Order", List.of(
                new Reasoner.Edge(orderId, "Order", "placedBy", customerId, "Customer", false, Optional.empty()),
                new Reasoner.Edge(customerId, "Customer", "memberOf", orgId, "Organization", false, Optional.empty())
        ));

        // When: Query each tenant separately
        Set<String> tenant1Orders = edgeRepo.srcIdsByDst(tenant1, "placedInOrg", orgId);
        Set<String> tenant2Orders = edgeRepo.srcIdsByDst(tenant2, "placedInOrg", orgId);

        // Then: Tenant isolation is enforced
        assertEquals(1, tenant1Orders.size(), "Tenant1 should have 1 order");
        assertTrue(tenant1Orders.contains(orderId), "Tenant1 should see its order");

        assertEquals(0, tenant2Orders.size(), "Tenant2 should have no orders");
    }

    private void inferAndStoreEdges(String entityId, String entityType, List<Reasoner.Edge> explicitEdges) {
        inferAndStoreEdges(TENANT, entityId, entityType, explicitEdges);
    }

    private void inferAndStoreEdges(String tenant, String entityId, String entityType, List<Reasoner.Edge> explicitEdges) {
        Reasoner.EntitySnapshot snapshot = new Reasoner.EntitySnapshot(tenant, entityId, entityType, explicitEdges);
        Reasoner.InferenceResult result = reasoner.infer(snapshot, ontologyRegistry);

        for (Reasoner.Edge edge : result.addEdges()) {
            Map<String, Object> prov = edge.prov().map(p -> Map.<String, Object>of("rule", p)).orElse(null);
            edgeRepo.upsert(tenant, edge.srcType(), edge.srcId(), edge.p(), edge.dstType(), edge.dstId(), edge.inferred(), prov);
    }
}
}
