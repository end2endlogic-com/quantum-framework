package com.e2eq.ontology.mongo;

import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
public class OntologyEdgeRepoTest {

    private static final String TENANT = "types-test";

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    MorphiaDatastore datastore;

    @BeforeEach
    void clean() {
        edgeRepo.deleteAll();
    }

    @Test
    void upsertWithTypes_persistsTypeMetadata() {
        edgeRepo.upsert(TENANT, "Order", "ORDER-1", "placedBy", "Customer", "CUST-1", false, Map.of());

        List<com.e2eq.ontology.model.OntologyEdge> edges = edgeRepo.findBySrc(TENANT, "ORDER-1");
        assertEquals(1, edges.size());
        com.e2eq.ontology.model.OntologyEdge edge = edges.get(0);
        assertEquals("Order", edge.getSrcType());
        assertEquals("Customer", edge.getDstType());
    }
}
