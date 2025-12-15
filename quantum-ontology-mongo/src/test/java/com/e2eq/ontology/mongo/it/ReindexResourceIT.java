package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.mongo.OntologyWriteHook;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.repo.OntologyMetaRepo;
import com.e2eq.ontology.service.OntologyReindexer;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ReindexResourceIT {

    private static final String TENANT = "test-system-com"; // default test realm used by tests

    @Inject
    MorphiaDatastore datastore;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    OntologyMetaRepo metaRepo;

    @Inject
    OntologyWriteHook writeHook;

    @Inject
    OntologyReindexer reindexer;
    
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
    public void reindex_force_is_idempotent_and_updates_version() throws Exception {
        // Seed data
        ItCustomer cust = new ItCustomer();
        cust.setRefName("CUST-9");
        cust.setDataDomain(testDataDomain);
        datastore.save(cust);

        ItOrg org = new ItOrg();
        org.setRefName("ORG-9");
        org.setDataDomain(testDataDomain);
        datastore.save(org);

        ItOrder order = new ItOrder();
        order.setRefName("ORDER-9");
        order.setDataDomain(testDataDomain);
        order.setPlacedBy(cust);
        order.setPlacedInOrg(org);
        datastore.save(order);
        // Trigger write hook to materialize initial edges
        writeHook.afterPersist(TENANT, order);

        // Baseline counts
        int orderEdgesBefore = edgeRepo.findBySrc(testDataDomain, "ORDER-9").size();
        int custEdgesBefore = edgeRepo.findBySrc(testDataDomain, "CUST-9").size();
        assertThat("Expect some edges to exist after write hook", orderEdgesBefore + custEdgesBefore, greaterThan(0));

        // Trigger reindex with force on service directly (avoids HTTP filters)
        reindexer.runAsync(TENANT, true);
        waitUntilCompleted(15000);

        // Version should be set in meta
        var meta = metaRepo.getSingleton().orElse(null);
        assertThat(meta, notNullValue());
        assertThat(meta.getYamlHash(), notNullValue());
        assertThat(meta.getAppliedAt(), notNullValue());

        // Counts after first reindex captured
        int orderEdgesAfter1 = edgeRepo.findBySrc(testDataDomain, "ORDER-9").size();
        int custEdgesAfter1 = edgeRepo.findBySrc(testDataDomain, "CUST-9").size();

        // Trigger again
        reindexer.runAsync(TENANT, true);
        waitUntilCompleted(15000);

        int orderEdgesAfter2 = edgeRepo.findBySrc(testDataDomain, "ORDER-9").size();
        int custEdgesAfter2 = edgeRepo.findBySrc(testDataDomain, "CUST-9").size();
        assertThat(orderEdgesAfter2, is(orderEdgesAfter1));
        assertThat(custEdgesAfter2, is(custEdgesAfter1));

        // Ensure expected inverse exists (customer placed order)
        List<com.e2eq.ontology.model.OntologyEdge> invEdges = edgeRepo.findBySrc(testDataDomain, "CUST-9");
        assertThat(invEdges.stream().anyMatch(e -> "placed".equals(e.getP()) && "ORDER-9".equals(e.getDst())), is(true));
    }

    private void waitUntilCompleted(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!reindexer.isRunning() && reindexer.status().startsWith("COMPLETED")) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Reindexer did not complete in time; status=" + reindexer.status());
    }
}
