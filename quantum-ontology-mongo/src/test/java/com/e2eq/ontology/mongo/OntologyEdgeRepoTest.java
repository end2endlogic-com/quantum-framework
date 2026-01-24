package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.exceptions.CardinalityViolationException;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class OntologyEdgeRepoTest {

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    MorphiaDatastore datastore;
    
    private DataDomain testDataDomain;
    private DataDomain testDataDomainOrgB;

    @BeforeEach
    void clean() {
        edgeRepo.deleteAll("types-test");
        // Create test DataDomains for isolation testing
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("test-org-a");
        testDataDomain.setAccountNum("1111111111");
        testDataDomain.setTenantId("types-test");
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);
        
        testDataDomainOrgB = new DataDomain();
        testDataDomainOrgB.setOrgRefName("test-org-b");
        testDataDomainOrgB.setAccountNum("2222222222");
        testDataDomainOrgB.setTenantId("types-test");
        testDataDomainOrgB.setOwnerId("system");
        testDataDomainOrgB.setDataSegment(0);
    }

    @Test
    void upsertWithTypes_persistsTypeMetadata() {
        edgeRepo.upsert(testDataDomain, "Order", "ORDER-1", "placedBy", "Customer", "CUST-1", false, Map.of());

        List<com.e2eq.ontology.model.OntologyEdge> edges = edgeRepo.findBySrc(testDataDomain, "ORDER-1");
        assertEquals(1, edges.size());
        com.e2eq.ontology.model.OntologyEdge edge = edges.get(0);
        assertEquals("Order", edge.getSrcType());
        assertEquals("Customer", edge.getDstType());
    }
    
    @Test
    void dataDomainIsolation_sameEdgeInDifferentOrgs_noCollision() {
        // Insert the same (src, p, dst) triple in two different organizations
        edgeRepo.upsert(testDataDomain, "Order", "ORDER-1", "placedBy", "Customer", "CUST-1", false, Map.of());
        edgeRepo.upsert(testDataDomainOrgB, "Order", "ORDER-1", "placedBy", "Customer", "CUST-1", false, Map.of());

        // Each organization should have its own edge
        List<com.e2eq.ontology.model.OntologyEdge> edgesOrgA = edgeRepo.findBySrc(testDataDomain, "ORDER-1");
        List<com.e2eq.ontology.model.OntologyEdge> edgesOrgB = edgeRepo.findBySrc(testDataDomainOrgB, "ORDER-1");
        
        assertEquals(1, edgesOrgA.size(), "Org A should have exactly 1 edge");
        assertEquals(1, edgesOrgB.size(), "Org B should have exactly 1 edge");
        
        // Verify DataDomain is correctly stored
        assertEquals("test-org-a", edgesOrgA.get(0).getDataDomain().getOrgRefName());
        assertEquals("test-org-b", edgesOrgB.get(0).getDataDomain().getOrgRefName());
    }
    
    @Test
    void dataDomainIsolation_queryReturnsOnlyMatchingDomain() {
        // Insert edges in both domains
        edgeRepo.upsert(testDataDomain, "Order", "ORDER-1", "placedBy", "Customer", "CUST-A", false, Map.of());
        edgeRepo.upsert(testDataDomainOrgB, "Order", "ORDER-1", "placedBy", "Customer", "CUST-B", false, Map.of());

        // Query for Org A should NOT return Org B's edge
        List<com.e2eq.ontology.model.OntologyEdge> edgesOrgA = edgeRepo.findBySrc(testDataDomain, "ORDER-1");
        assertEquals(1, edgesOrgA.size());
        assertEquals("CUST-A", edgesOrgA.get(0).getDst());
        
        // Verify no cross-org leakage
        boolean foundCustB = edgesOrgA.stream().anyMatch(e -> "CUST-B".equals(e.getDst()));
        assertTrue(!foundCustB, "Org A query should NOT return Org B's edge");
    }

    // ========================================================================
    // Functional Constraint Tests
    // ========================================================================

    @Test
    void functionalProperty_allowsIdempotentUpsert() {
        // First upsert should succeed
        edgeRepo.upsert(testDataDomain, "Order", "ORDER-1", "placedBy", "Customer", "CUST-1", false, Map.of());

        // Same upsert (idempotent) should succeed without throwing
        assertDoesNotThrow(() ->
            edgeRepo.upsert(testDataDomain, "Order", "ORDER-1", "placedBy", "Customer", "CUST-1", false, Map.of())
        );

        // Verify only one edge exists
        List<com.e2eq.ontology.model.OntologyEdge> edges = edgeRepo.findBySrc(testDataDomain, "ORDER-1");
        assertEquals(1, edges.size());
    }

    @Test
    void functionalProperty_throwsOnViolation() {
        // First upsert should succeed (placedBy is functional in TestOntologyRegistryProducer)
        edgeRepo.upsert(testDataDomain, "Order", "ORDER-1", "placedBy", "Customer", "CUST-1", false, Map.of());

        // Second upsert with DIFFERENT destination should throw CardinalityViolationException
        CardinalityViolationException exception = assertThrows(
            CardinalityViolationException.class,
            () -> edgeRepo.upsert(testDataDomain, "Order", "ORDER-1", "placedBy", "Customer", "CUST-2", false, Map.of())
        );

        // Verify exception contains useful information
        assertEquals("placedBy", exception.getPredicate());
        assertEquals("ORDER-1", exception.getSourceId());
        assertEquals("CUST-1", exception.getExistingTargetId());
        assertEquals("CUST-2", exception.getAttemptedTargetId());
    }

    @Test
    void nonFunctionalProperty_allowsMultipleEdges() {
        // memberOf is NOT functional in TestOntologyRegistryProducer (functional=false)
        edgeRepo.upsert(testDataDomain, "Customer", "CUST-1", "memberOf", "Organization", "ORG-1", false, Map.of());
        edgeRepo.upsert(testDataDomain, "Customer", "CUST-1", "memberOf", "Organization", "ORG-2", false, Map.of());

        // Both edges should exist
        List<com.e2eq.ontology.model.OntologyEdge> edges = edgeRepo.findBySrc(testDataDomain, "CUST-1");
        assertEquals(2, edges.size(), "Non-functional property should allow multiple edges");
    }

    // ========================================================================
    // Single-Value Convenience Method Tests
    // ========================================================================

    @Test
    void singleDstIdBySrc_returnsEmptyWhenNoEdge() {
        Optional<String> result = edgeRepo.singleDstIdBySrc(testDataDomain, "placedBy", "NONEXISTENT");
        assertTrue(result.isEmpty());
    }

    @Test
    void singleDstIdBySrc_returnsSingleValue() {
        edgeRepo.upsert(testDataDomain, "Order", "ORDER-1", "placedBy", "Customer", "CUST-1", false, Map.of());

        Optional<String> result = edgeRepo.singleDstIdBySrc(testDataDomain, "placedBy", "ORDER-1");
        assertTrue(result.isPresent());
        assertEquals("CUST-1", result.get());
    }

    @Test
    void singleSrcIdByDst_returnsEmptyWhenNoEdge() {
        Optional<String> result = edgeRepo.singleSrcIdByDst(testDataDomain, "placedBy", "NONEXISTENT");
        assertTrue(result.isEmpty());
    }

    @Test
    void singleSrcIdByDst_returnsSingleValue() {
        edgeRepo.upsert(testDataDomain, "Order", "ORDER-1", "placedBy", "Customer", "CUST-1", false, Map.of());

        Optional<String> result = edgeRepo.singleSrcIdByDst(testDataDomain, "placedBy", "CUST-1");
        assertTrue(result.isPresent());
        assertEquals("ORDER-1", result.get());
    }

    @Test
    void singleSrcIdByDst_throwsWhenMultipleSources() {
        // memberOf is non-functional, so we can create multiple edges pointing to same destination
        edgeRepo.upsert(testDataDomain, "Customer", "CUST-1", "memberOf", "Organization", "ORG-1", false, Map.of());
        edgeRepo.upsert(testDataDomain, "Customer", "CUST-2", "memberOf", "Organization", "ORG-1", false, Map.of());

        // singleSrcIdByDst should throw because there are multiple sources
        assertThrows(
            CardinalityViolationException.class,
            () -> edgeRepo.singleSrcIdByDst(testDataDomain, "memberOf", "ORG-1")
        );
    }
}
