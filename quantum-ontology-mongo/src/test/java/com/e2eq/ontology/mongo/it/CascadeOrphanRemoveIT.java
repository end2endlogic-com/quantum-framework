package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.mongo.OntologyWriteHook;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class CascadeOrphanRemoveIT {

    private static final String TENANT = "test-system-com"; // default test realm from framework tests

    @Inject
    MorphiaDatastore datastore;
    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    OntologyWriteHook writeHook;
    
    private DataDomain testDataDomain;

    @BeforeEach
    void clean() {
        edgeRepo.deleteAll();
        datastore.getDatabase().getCollection("it_children").deleteMany(new Document());
        datastore.getDatabase().getCollection("it_parents").deleteMany(new Document());
        
        // Create test DataDomain
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("test-org");
        testDataDomain.setAccountNum("1234567890");
        testDataDomain.setTenantId(TENANT);
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);
    }

    @Test
    public void orphanRemove_deletesChildWhenNoOtherReferences() {
        // given two children and a parent referencing both
        ItChild c1 = new ItChild(); c1.setRefName("CH-1"); c1.setDataDomain(testDataDomain); datastore.save(c1);
        ItChild c2 = new ItChild(); c2.setRefName("CH-2"); c2.setDataDomain(testDataDomain); datastore.save(c2);

        ItParent p = new ItParent(); p.setRefName("P-1"); p.setDataDomain(testDataDomain);
        p.getChildren().add(c1); p.getChildren().add(c2);
        datastore.save(p);
        writeHook.afterPersist(TENANT, p);

        // edges exist
        assertHasEdges("P-1", Set.of("CH-1", "CH-2"));

        // when removing c2 from parent children
        p.setChildren(new ArrayList<>(List.of(c1)));
        datastore.save(p);
        writeHook.afterPersist(TENANT, p);

        // then edge to CH-2 is gone; deletion of child is optional depending on cascade configuration
        assertHasEdges("P-1", Set.of("CH-1"));
    }

    @Test
    public void orphanRemove_doesNotDeleteIfStillReferencedElsewhere() {
        // given a shared child referenced by two parents
        ItChild c = new ItChild(); c.setRefName("CH-X"); c.setDataDomain(testDataDomain); datastore.save(c);
        ItParent p1 = new ItParent(); p1.setRefName("P-1"); p1.setDataDomain(testDataDomain); p1.getChildren().add(c); datastore.save(p1); writeHook.afterPersist(TENANT, p1);
        ItParent p2 = new ItParent(); p2.setRefName("P-2"); p2.setDataDomain(testDataDomain); p2.getChildren().add(c); datastore.save(p2); writeHook.afterPersist(TENANT, p2);

        // sanity: edges from both parents
        assertHasEdges("P-1", Set.of("CH-X"));
        assertHasEdges("P-2", Set.of("CH-X"));

        // when removing from first parent only
        p1.setChildren(new ArrayList<>());
        datastore.save(p1);
        writeHook.afterPersist(TENANT, p1);

        // then child remains (still referenced by p2)
        long remaining = datastore.getDatabase().getCollection("it_children")
                .countDocuments(new Document("refName", "CH-X"));
        assertEquals(1, remaining, "shared child should not be deleted");
        // and p1 has no edge now
        assertHasEdges("P-1", Set.of());
        // p2 still has edge
        assertHasEdges("P-2", Set.of("CH-X"));
    }

    private void assertHasEdges(String src, Set<String> expectedDsts) {
        var edges = edgeRepo.findBySrc(testDataDomain, src);
        var dsts = edges.stream().filter(e -> e.getP().equals("HAS_CHILD")).map(e -> e.getDst()).collect(Collectors.toSet());
        assertEquals(expectedDsts, dsts, "edges mismatch for src=" + src);
    }
}
