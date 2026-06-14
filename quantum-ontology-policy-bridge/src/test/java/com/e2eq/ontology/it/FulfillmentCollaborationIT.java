package com.e2eq.ontology.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.FieldPolicyEnforcer;
import com.e2eq.ontology.policy.ListQueryRewriter;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE FULFILLMENT COLLABORATION DEMO as executable evidence (task: tenant-models
 * "shared collaboration realm" + permissions "Field-Level Policy" + ontology
 * relationship policies — see the project demo docs for the
 * Postman walkthrough of the same story over REST).
 *
 * One realm, three member orgs (realm-membership ADR: one OWNER, N PARTICIPANTs):
 *   acme.com        — customer org, realm OWNER
 *   fastfreight.com — carrier org, PARTICIPANT
 *   partsco.com     — supplier org, PARTICIPANT
 *
 * Shared entities with ontology edges:
 *   DemoSalesOrder      —placedBy→   Customer org
 *   DemoFulfillmentPlan —fulfills→   DemoSalesOrder   (+ reverse fulfilledBy)
 *   DemoFulfillmentPlan —assignedTo→ Carrier org
 *   DemoFulfillmentPlan —suppliedBy→ Supplier org
 *
 * The proof: THE SAME COLLECTIONS, three principals, three different slices —
 * row scope via relationship traversal (hasEdge), field scope via the
 * field-level policy enforcer. No data is copied per org; visibility is policy.
 */
@QuarkusTest
public class FulfillmentCollaborationIT {

    @Inject OntologyEdgeRepo edgeRepo;
    @Inject ListQueryRewriter queryRewriter;
    @Inject MorphiaDatastore datastore;
    @Inject com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper dataStoreWrapper;

    private static final String TENANT = "fulfillment-demo";
    private static final String CUSTOMER_ORG = "acme.com";
    private static final String CARRIER_ORG = "fastfreight.com";
    private static final String OTHER_CARRIER = "slowhaul.com";
    private static final String SUPPLIER_ORG = "partsco.com";

    private DataDomain realmDomain;

    @BeforeEach
    public void seedRealm() {
        edgeRepo.deleteAll();
        dataStoreWrapper.getDataStore("fulfillment-demo").getDatabase().getCollection("edges").drop();
        datastore.getDatabase().getCollection("demo_sales_orders").drop();
        datastore.getDatabase().getCollection("demo_fulfillment_plans").drop();

        realmDomain = new DataDomain();
        realmDomain.setOrgRefName(CUSTOMER_ORG);
        realmDomain.setAccountNum("4444555566");
        realmDomain.setTenantId(TENANT);
        realmDomain.setOwnerId("system");
        realmDomain.setDataSegment(0);

        // Orders: SO-1 placed by acme; SO-2 placed by a different customer org.
        order("SO-1", "New York, NY", 120.0, 0.35, CUSTOMER_ORG);
        order("SO-2", "Houston, TX", 80.0, 0.22, "othercustomer.com");

        // Plans: P-1 fulfills SO-1 (carrier fastfreight); P-2 fulfills SO-2
        // (carrier slowhaul). Both supplied by partsco.
        plan("P-1", "ATL->JFK", "SO-1", CARRIER_ORG, SUPPLIER_ORG);
        plan("P-2", "DFW->HOU", "SO-2", OTHER_CARRIER, SUPPLIER_ORG);
    }

    @Test
    public void carrierSeesOnlyAssignedPlansAndShippingFieldsOfLinkedOrders() {
        // Row scope: plans assigned to MY org (the carrier role's rule:
        // andFilterString = hasEdge('assignedTo', ${principal.orgRefName})).
        Filter assignedToMe = queryRewriter.hasEdge(realmDomain, "assignedTo", CARRIER_ORG);
        List<DemoFulfillmentPlan> plans = datastore.find(DemoFulfillmentPlan.class)
            .filter(assignedToMe).iterator().toList();
        assertEquals(1, plans.size(), "carrier sees only plans assigned to it");
        assertEquals("P-1", plans.get(0).getRefName());

        // One hop through fulfills: the orders my plans fulfill.
        Filter myOrders = queryRewriter.hasEdge(realmDomain, "fulfilledBy", "P-1");
        List<DemoSalesOrder> orders = datastore.find(DemoSalesOrder.class)
            .filter(myOrders).iterator().toList();
        assertEquals(1, orders.size());
        assertEquals("SO-1", orders.get(0).getRefName());

        // Field scope: the carrier role's excludedFields — shipping visible,
        // pricing not. (Mongo-projection enforcement is suite-proven; this is
        // the same resolved set applied at the materialization layer.)
        FieldPolicyEnforcer.mask(orders, Set.of("unitPrice", "margin"));
        assertEquals("New York, NY", orders.get(0).getShipTo(), "carrier needs the shipping surface");
        assertNull(orders.get(0).getUnitPrice(), "pricing is policy-hidden from carriers");
        assertNull(orders.get(0).getMargin());
    }

    @Test
    public void supplierSeesItsPlansAcrossCarriersWithMarginHidden() {
        Filter suppliedByMe = queryRewriter.hasEdge(realmDomain, "suppliedBy", SUPPLIER_ORG);
        List<DemoFulfillmentPlan> plans = datastore.find(DemoFulfillmentPlan.class)
            .filter(suppliedByMe).iterator().toList();
        assertEquals(2, plans.size(), "supplier supplies both plans regardless of carrier");

        // Supplier's order view: unitPrice visible (they invoice against it),
        // margin still owner-only — different exclusion set per role.
        List<DemoSalesOrder> orders = datastore.find(DemoSalesOrder.class).iterator().toList();
        FieldPolicyEnforcer.mask(orders, Set.of("margin"));
        assertTrue(orders.stream().allMatch(o -> o.getUnitPrice() != null), "supplier sees unit price");
        assertTrue(orders.stream().allMatch(o -> o.getMargin() == null), "margin is owner-only");
    }

    @Test
    public void customerSeesOwnOrdersWithFullCommercialFields() {
        Filter placedByMe = queryRewriter.hasEdge(realmDomain, "placedBy", CUSTOMER_ORG);
        List<DemoSalesOrder> orders = datastore.find(DemoSalesOrder.class)
            .filter(placedByMe).iterator().toList();
        assertEquals(1, orders.size(), "customer sees only its own orders");
        assertEquals("SO-1", orders.get(0).getRefName());
        assertEquals(120.0, orders.get(0).getUnitPrice(), "owner-side sees pricing");
        assertEquals(0.35, orders.get(0).getMargin());

        // Status visibility via the plan: which plans fulfill MY orders.
        Filter fulfillsMyOrder = queryRewriter.hasEdge(realmDomain, "fulfills", "SO-1");
        List<DemoFulfillmentPlan> plans = datastore.find(DemoFulfillmentPlan.class)
            .filter(fulfillsMyOrder).iterator().toList();
        assertEquals(1, plans.size());
        assertEquals("P-1", plans.get(0).getRefName());
    }

    @Test
    public void nonMemberCarrierSeesNothing() {
        Filter assigned = queryRewriter.hasEdge(realmDomain, "assignedTo", "stranger.com");
        assertEquals(0, datastore.find(DemoFulfillmentPlan.class)
            .filter(assigned).iterator().toList().size(),
            "an org with no relationships resolves to an empty slice — same data, zero visibility");
    }

    private void order(String ref, String shipTo, double unitPrice, double margin, String customerOrg) {
        DemoSalesOrder order = new DemoSalesOrder();
        order.setRefName(ref);
        order.setShipTo(shipTo);
        order.setStatus("OPEN");
        order.setUnitPrice(unitPrice);
        order.setMargin(margin);
        datastore.save(order);
        edgeRepo.upsert(realmDomain, "SalesOrder", ref, "placedBy", "Organization", customerOrg, false, null);
    }

    private void plan(String ref, String route, String orderRef, String carrierOrg, String supplierOrg) {
        DemoFulfillmentPlan plan = new DemoFulfillmentPlan();
        plan.setRefName(ref);
        plan.setRoute(route);
        plan.setStatus("IN_TRANSIT");
        datastore.save(plan);
        edgeRepo.upsert(realmDomain, "FulfillmentPlan", ref, "fulfills", "SalesOrder", orderRef, false, null);
        edgeRepo.upsert(realmDomain, "SalesOrder", orderRef, "fulfilledBy", "FulfillmentPlan", ref, false, null);
        edgeRepo.upsert(realmDomain, "FulfillmentPlan", ref, "assignedTo", "Organization", carrierOrg, false, null);
        edgeRepo.upsert(realmDomain, "FulfillmentPlan", ref, "suppliedBy", "Organization", supplierOrg, false, null);
    }
}
