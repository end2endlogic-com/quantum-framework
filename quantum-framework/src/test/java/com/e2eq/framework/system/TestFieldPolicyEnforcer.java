package com.e2eq.framework.system;

import com.e2eq.framework.model.securityrules.FieldPolicyEnforcer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/** Plain unit test for the field-level policy enforcement primitive. */
public class TestFieldPolicyEnforcer {

    static class Pricing {
        Double unitPrice;
        Double margin;
        Pricing(Double unitPrice, Double margin) { this.unitPrice = unitPrice; this.margin = margin; }
    }

    static class Order {
        String orderId;
        String shippingAddress;
        Pricing pricing;
        Order(String orderId, String shippingAddress, Pricing pricing) {
            this.orderId = orderId; this.shippingAddress = shippingAddress; this.pricing = pricing;
        }
    }

    @Test
    public void masksTopLevelAndNestedPaths() {
        Order order = new Order("O-1", "12 Main St", new Pricing(10.5, 0.3));

        FieldPolicyEnforcer.mask(order, Set.of("shippingAddress", "pricing.margin"));

        Assertions.assertEquals("O-1", order.orderId);
        Assertions.assertNull(order.shippingAddress);
        Assertions.assertEquals(10.5, order.pricing.unitPrice);
        Assertions.assertNull(order.pricing.margin);
    }

    @Test
    public void masksCollectionsElementWiseAndIgnoresUnknownPaths() {
        List<Order> orders = List.of(
            new Order("O-1", "A", new Pricing(1.0, 0.1)),
            new Order("O-2", "B", new Pricing(2.0, 0.2)));

        FieldPolicyEnforcer.mask(orders, Set.of("pricing", "noSuchField", "no.such.path"));

        for (Order order : orders) {
            Assertions.assertNull(order.pricing);
            Assertions.assertNotNull(order.orderId);
        }
    }

    @Test
    public void copyPathRestoresStoredValueOverIncomingOverwrite() throws Exception {
        Order stored = new Order("O-1", "12 Main St", new Pricing(10.5, 0.3));
        Order incoming = new Order("O-1", "12 Main St", new Pricing(999.0, 0.99));

        FieldPolicyEnforcer.copyPath(stored, incoming, "pricing.unitPrice");

        Assertions.assertEquals(10.5, incoming.pricing.unitPrice, "hidden field overwrite must be reverted");
        Assertions.assertEquals(0.99, incoming.pricing.margin, "non-protected sibling stays");
    }
}
