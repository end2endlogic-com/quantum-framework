package com.e2eq.ontology.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.core.ForwardChainingReasoner;
import com.e2eq.ontology.mongo.EdgeDao;
import com.e2eq.ontology.mongo.OntologyMaterializer;
import com.e2eq.ontology.mongo.EdgeRelationStore;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class OntologyEndToEndIntegrationTest {

    private static final String REALM = "ontology-it";

    @Inject MongoClient mongoClient;
    // Reasoner/Registry/Materializer constructed manually in test body
    @Inject TestOrderRepo orderRepo;

    private MongoCollection<Document> ordersRaw;

    @BeforeEach
    void setup() {
        mongoClient.getDatabase(REALM).drop();
        ordersRaw = mongoClient.getDatabase(REALM).getCollection("orders");

        // Establish a minimal security context so MorphiaRepo can resolve realms and data domains
        DataDomain dd = new DataDomain("org-ont", "0000000001", REALM, 0, "owner-it");
        PrincipalContext pc = new PrincipalContext.Builder()
                .withDefaultRealm(REALM)
                .withDataDomain(dd)
                .withUserId("user@" + REALM)
                .withRoles(new String[]{"ADMIN"})
                .withScope("it")
                .build();
        ResourceContext rcList = new ResourceContext.Builder()
                .withRealm(REALM)
                .withArea("INTEGRATION")
                .withFunctionalDomain("ORDERS")
                .withAction("LIST")
                .build();
        SecurityContext.setPrincipalContext(pc);
        SecurityContext.setResourceContext(rcList);

        // Seed some orders in the realm using raw documents; refName will be used as the logical id
        ordersRaw.insertMany(java.util.List.of(
                new org.bson.Document("_id", "ORD1").append("refName", "ORD1").append("status", "OPEN")
                        .append("dataDomain", new org.bson.Document("tenantId", REALM).append("orgRefName", "org-ont")),
                new org.bson.Document("_id", "ORD2").append("refName", "ORD2").append("status", "OPEN")
                        .append("dataDomain", new org.bson.Document("tenantId", REALM).append("orgRefName", "org-ont")),
                new org.bson.Document("_id", "ORD3").append("refName", "ORD3").append("status", "CLOSED")
                        .append("dataDomain", new org.bson.Document("tenantId", REALM).append("orgRefName", "org-ont"))
        ));

        // Clear edges before each test
        mongoClient.getDatabase(REALM).getCollection("edges").deleteMany(new Document());
    }

    @AfterEach
    void cleanup() {
        SecurityContext.clear();
    }

    @Test
    void endToEnd_materialize_edges_prune_and_rewrite_and_repoList() {
        // Arrange explicit edges encoding placedBy and memberOf to infer placedInOrg
        String tenantId = REALM;
        String order = "ORD1";
        String customer = "C9";
        String orgA = "OrgA";

        // Pre-existing obsolete inferred edge that should be pruned
        mongoClient.getDatabase(REALM).getCollection("edges")
                .insertOne(new Document("tenantId", tenantId)
                        .append("src", order)
                        .append("p", "placedInOrg")
                        .append("dst", "OrgOld")
                        .append("inferred", true));

        List<Reasoner.Edge> explicit = List.of(
                new Reasoner.Edge(order, "placedBy", customer, false, Optional.empty()),
                new Reasoner.Edge(customer, "memberOf", orgA, false, Optional.empty())
        );

        // Build ontology components manually (avoid CDI for this E2E test)
        EdgeDao edgeDao = new EdgeDao();
        try {
            java.lang.reflect.Field f1 = EdgeDao.class.getDeclaredField("mongoClient");
            f1.setAccessible(true);
            f1.set(edgeDao, mongoClient);
            java.lang.reflect.Field f2 = EdgeDao.class.getDeclaredField("databaseName");
            f2.setAccessible(true);
            f2.set(edgeDao, REALM);
            java.lang.reflect.Field f3 = EdgeDao.class.getDeclaredField("collectionName");
            f3.setAccessible(true);
            f3.set(edgeDao, "edges");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // initialize collection and indexes
        try {
            java.lang.reflect.Method init = EdgeDao.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(edgeDao);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Reasoner reasoner = new ForwardChainingReasoner();
        OntologyRegistry registry = new TestOntologyRegistryProducer().ontologyRegistry();
        OntologyMaterializer materializer = new OntologyMaterializer(reasoner, registry, edgeDao);

        // Act: materialize
        materializer.apply(tenantId, order, "Order", explicit);

        // Assert: inferred edge exists and obsolete pruned
        List<Document> edgesForOrder = edgeDao.findBySrc(tenantId, order);
        boolean hasOrgA = edgesForOrder.stream().anyMatch(d ->
                tenantId.equals(d.getString("tenantId")) &&
                        order.equals(d.getString("src")) &&
                        "placedInOrg".equals(d.getString("p")) &&
                        orgA.equals(d.getString("dst")) &&
                        Boolean.TRUE.equals(d.getBoolean("inferred"))
        );
        assertTrue(hasOrgA, "Expected inferred placedInOrg edge to OrgA");
        boolean hasOrgOld = edgesForOrder.stream().anyMatch(d -> "OrgOld".equals(d.getString("dst")));
        assertFalse(hasOrgOld, "Obsolete inferred edge should be pruned");

        // MorphiaRepo list API: use repository method that constrains by ontology ids
        List<TestOrder> repoList = orderRepo.listByHasEdge(REALM, "placedInOrg", orgA, edgeDao);
        assertEquals(1, repoList.size());
        assertEquals("ORD1", repoList.get(0).getRefName());
    }

}
