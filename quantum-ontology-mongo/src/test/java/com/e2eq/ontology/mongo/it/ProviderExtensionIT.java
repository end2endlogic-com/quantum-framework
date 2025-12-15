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

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class ProviderExtensionIT {

    private static final String TENANT = "test-system-com";

    @Inject MorphiaDatastore datastore;
    @Inject OntologyEdgeRepo edgeRepo;
    @Inject OntologyWriteHook writeHook;
    
    private DataDomain testDataDomain;

    @BeforeEach
    void clean() {
        edgeRepo.deleteAll();
        datastore.getDatabase().getCollection("it_prov_targets").deleteMany(new Document());
        datastore.getDatabase().getCollection("it_prov_sources").deleteMany(new Document());
        
        // Create test DataDomain
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("test-org");
        testDataDomain.setAccountNum("1234567890");
        testDataDomain.setTenantId(TENANT);
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);
    }

    @Test
    public void writeHook_mergesAnnotationAndProviderEdges() {
        // Given two targets
        ItProvTarget t1 = new ItProvTarget(); t1.setRefName("TGT-1"); t1.setDataDomain(testDataDomain); datastore.save(t1);
        ItProvTarget t2 = new ItProvTarget(); t2.setRefName("TGT-2"); t2.setDataDomain(testDataDomain); datastore.save(t2);

        // And a source that references t1 via annotation, and t2 via provider field
        ItProvSource s = new ItProvSource();
        s.setRefName("SRC-1");
        s.setDataDomain(testDataDomain);
        s.setAnnotatedTarget(t1);
        s.setProviderTargetRef("TGT-2");
        datastore.save(s);

        // When: trigger write hook
        writeHook.afterPersist(TENANT, s);

        // Then: both edges should exist explicitly for SRC-1
        var edges = edgeRepo.findBySrc(testDataDomain, "SRC-1");
        long annCount = edges.stream().filter(e -> !e.isInferred() && e.getP().equals("annRel") && e.getDst().equals("TGT-1")).count();
        long provCount = edges.stream().filter(e -> !e.isInferred() && e.getP().equals("provRel") && e.getDst().equals("TGT-2")).count();
        assertEquals(1, annCount, "Annotation-derived edge missing");
        assertEquals(1, provCount, "Provider-derived edge missing");
        long explicit = edges.stream().filter(e -> !e.isInferred()).count();
        assertEquals(2, explicit, "Total explicit edges should be 2");
    }
}
