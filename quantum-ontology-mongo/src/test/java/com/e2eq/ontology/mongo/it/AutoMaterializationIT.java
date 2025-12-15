package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.mongo.OntologyWriteHook;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class AutoMaterializationIT {

    private static final String TENANT = "test-system-com"; // default test realm from framework tests

    @Inject
    MorphiaDatastore datastore;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    OntologyWriteHook writeHook;
    
    private DataDomain testDataDomain;

    @BeforeEach
    void clean() {
        // Clean edges and entity collections between tests
        edgeRepo.deleteAll();
        datastore.getDatabase().getCollection("it_orders").deleteMany(new Document());
        datastore.getDatabase().getCollection("it_customers").deleteMany(new Document());
        datastore.getDatabase().getCollection("it_orgs").deleteMany(new Document());
        
        // Create test DataDomain
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("test-org");
        testDataDomain.setAccountNum("1234567890");
        testDataDomain.setTenantId(TENANT);
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);
    }

    @Test
    public void savesOrder_triggersAutoMaterialization_infersInverseAndSuperProperty() {
        // Given: a customer and an org
        ItCustomer cust = new ItCustomer();
        cust.setRefName("CUST-1");
        cust.setDataDomain(testDataDomain);
        datastore.save(cust);

        ItOrg org = new ItOrg();
        org.setRefName("ORG-ACME");
        org.setDataDomain(testDataDomain);
        datastore.save(org);

        // When: save an order that references them via annotated fields
        ItOrder order = new ItOrder();
        order.setRefName("ORDER-1");
        order.setDataDomain(testDataDomain);
        order.setPlacedBy(cust);            // annotated with @OntologyProperty placedBy inverseOf placed
        order.setPlacedInOrg(org);          // annotated as subPropertyOf inOrg
        datastore.save(order);

        // And: trigger the write hook (MorphiaRepo does this in real saves; we call directly here)
        writeHook.afterPersist(TENANT, order);

        // Then: infer inverse for placedBy: (customer) placed order
        var invEdges = edgeRepo.findBySrc(testDataDomain, "CUST-1");
        boolean hasInverse = invEdges.stream().anyMatch(e -> e.getP().equals("placed") && e.getDst().equals("ORDER-1"));
        assertTrue(hasInverse, "inverse edge 'placed' should be inferred for customer -> order");
    }
}
