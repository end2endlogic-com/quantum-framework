package com.e2eq.ontology.it;

import com.e2eq.framework.model.persistent.morphia.QueryToFilterListener;
import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.ontology.core.ForwardChainingReasoner;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
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
 * Integration test demonstrating hasEdge grammar operator usage
 * Shows that hasEdge(predicate, dst) in query string produces same results as ListQueryRewriter.hasEdge()
 */
@QuarkusTest
public class HasEdgeGrammarIT {

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
        datastore.getDatabase().getCollection("orders").drop();
    }

    @Test
    public void testHasEdgeGrammar_EquivalentToListQueryRewriter() {
        // Given: Orders in different organizations
        createOrder("ORDER-001", "OPEN");
        createOrder("ORDER-002", "OPEN");
        createOrder("ORDER-003", "CLOSED");
        createOrder("ORDER-004", "OPEN");

        setupOrderInOrg("ORDER-001", "CUST-A", "ORG-ACME");
        setupOrderInOrg("ORDER-002", "CUST-B", "ORG-ACME");
        setupOrderInOrg("ORDER-003", "CUST-C", "ORG-ACME");
        setupOrderInOrg("ORDER-004", "CUST-D", "ORG-OTHER");

        // When: Query using ListQueryRewriter directly
        Filter statusFilter = Filters.eq("status", "OPEN");
        Filter orgFilter = queryRewriter.hasEdge(TENANT, "placedInOrg", "ORG-ACME");
        Filter combinedFilter = Filters.and(statusFilter, orgFilter);

        List<TestOrder> directResults = datastore.find(TestOrder.class)
                .filter(combinedFilter)
                .iterator()
                .toList();

        // Then: Verify direct query works
        assertEquals(2, directResults.size(), "Direct query should find 2 OPEN orders in ORG-ACME");
        assertTrue(directResults.stream().anyMatch(o -> o.getRefName().equals("ORDER-001")));
        assertTrue(directResults.stream().anyMatch(o -> o.getRefName().equals("ORDER-002")));

        // When: Query using grammar with hasEdge expression
        String grammarQuery = "status:OPEN && hasEdge(placedInOrg, ORG-ACME)";
        Filter grammarFilter = parseQuery(grammarQuery, TENANT);
        
        List<TestOrder> grammarResults = datastore.find(TestOrder.class)
                .filter(grammarFilter)
                .iterator()
                .toList();

        // Then: Grammar-based query should produce identical results
        assertEquals(directResults.size(), grammarResults.size(),
                "Grammar query should produce same count as direct query");
        assertEquals(
                directResults.stream().map(TestOrder::getRefName).sorted().toList(),
                grammarResults.stream().map(TestOrder::getRefName).sorted().toList(),
                "Grammar query should produce same orders as direct query"
        );
    }

    @Test
    public void testHasEdgeGrammar_WithMultipleConditions() {
        // Given: Complex scenario with multiple filters
        createOrder("ORDER-001", "OPEN");
        createOrder("ORDER-002", "PENDING");
        createOrder("ORDER-003", "OPEN");
        createOrder("ORDER-004", "OPEN");

        setupOrderInOrg("ORDER-001", "CUST-A", "ORG-ACME");
        setupOrderInOrg("ORDER-002", "CUST-B", "ORG-ACME");
        setupOrderInOrg("ORDER-003", "CUST-C", "ORG-GLOBEX");
        setupOrderInOrg("ORDER-004", "CUST-D", "ORG-OTHER");

        // When: Complex query with OR of hasEdge expressions
        String grammarQuery = "status:OPEN && (hasEdge(placedInOrg, ORG-ACME) || hasEdge(placedInOrg, ORG-GLOBEX))";
        Filter grammarFilter = parseQuery(grammarQuery, TENANT);
        
        List<TestOrder> results = datastore.find(TestOrder.class)
                .filter(grammarFilter)
                .iterator()
                .toList();

        // Then: Should find OPEN orders from both organizations
        assertEquals(2, results.size(), "Should find 2 OPEN orders from ACME or GLOBEX");
        assertTrue(results.stream().anyMatch(o -> o.getRefName().equals("ORDER-001")));
        assertTrue(results.stream().anyMatch(o -> o.getRefName().equals("ORDER-003")));
    }

    @Test
    public void testHasEdgeGrammar_EmptyResult() {
        // Given: Orders exist but none match the edge criteria
        createOrder("ORDER-001", "OPEN");
        setupOrderInOrg("ORDER-001", "CUST-A", "ORG-OTHER");

        // When: Query for non-existent organization using grammar
        String grammarQuery = "hasEdge(placedInOrg, ORG-NONEXISTENT)";
        Filter grammarFilter = parseQuery(grammarQuery, TENANT);
        
        List<TestOrder> results = datastore.find(TestOrder.class)
                .filter(grammarFilter)
                .iterator()
                .toList();

        assertEquals(0, results.size(), "Should return no results");
    }

    private void createOrder(String refName, String status) {
        TestOrder order = new TestOrder();
        order.setRefName(refName);
        order.setStatus(status);
        datastore.save(order);
    }

    private Filter parseQuery(String query, String tenantId) {
        CharStream cs = CharStreams.fromString(query);
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);
        BIAPIQueryParser.QueryContext tree = parser.query();
        
        Map<String, String> vars = Map.of("pTenantId", tenantId);
        QueryToFilterListener listener = new QueryToFilterListener(vars, null, null);
        ParseTreeWalker.DEFAULT.walk(listener, tree);
        return listener.getFilter();
    }
    
    private void setupOrderInOrg(String orderId, String customerId, String orgId) {
        edgeRepo.upsert(TENANT, orderId, "placedBy", customerId, false, null);
        edgeRepo.upsert(TENANT, customerId, "memberOf", orgId, false, null);

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
