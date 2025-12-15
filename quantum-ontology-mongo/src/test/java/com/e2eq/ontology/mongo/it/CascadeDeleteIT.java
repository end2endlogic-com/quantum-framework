package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.ontology.mongo.OntologyWriteHook;
import com.e2eq.ontology.mongo.OntologyDeleteHook;
import com.e2eq.ontology.mongo.CascadeExecutor;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class CascadeDeleteIT {

    private static final String TENANT = "test-system-com";

    @Inject MorphiaDatastore datastore;
    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;
    @Inject OntologyEdgeRepo edgeRepo;
    @Inject OntologyWriteHook writeHook;
    @Inject OntologyDeleteHook deleteHook;
    @Inject CascadeExecutor cascadeExecutor;
    
    private DataDomain testDataDomain;

    @BeforeEach
    void clean() {
        edgeRepo.deleteAll();
        datastore.getDatabase().getCollection("it_children").deleteMany(new Document());
        datastore.getDatabase().getCollection("it_parents_del").deleteMany(new Document());
        
        // Create test DataDomain
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("test-org");
        testDataDomain.setAccountNum("1234567890");
        testDataDomain.setTenantId(TENANT);
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);
    }

    @Test
    public void deleteCascade_removesChildren() {
        // create two children
        ItChild c1 = new ItChild(); c1.setRefName("CD-1"); c1.setDataDomain(testDataDomain); datastore.save(c1);
        ItChild c2 = new ItChild(); c2.setRefName("CD-2"); c2.setDataDomain(testDataDomain); datastore.save(c2);

        // parent with DELETE cascade
        ItParentDel p = new ItParentDel(); p.setRefName("PDEL-1"); p.setDataDomain(testDataDomain);
        p.getChildren().add(c1); p.getChildren().add(c2);
        datastore.save(p);
        // materialize edges using injected hook
        writeHook.afterPersist(TENANT, p);

        // now delete parent using cascade executor directly (simulates repository delete wiring)
        cascadeExecutor.onAfterDelete(testDataDomain, "ParentDel", "PDEL-1");

        // verify children are deleted
        long left1 = datastore.getDatabase().getCollection("it_children").countDocuments(new Document("refName", "CD-1"));
        long left2 = datastore.getDatabase().getCollection("it_children").countDocuments(new Document("refName", "CD-2"));
        assertEquals(0, left1 + left2, "Children should be deleted by cascade");
    }
}
