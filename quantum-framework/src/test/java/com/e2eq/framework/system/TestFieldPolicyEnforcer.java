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

    static class PrimitiveRecord {
        boolean enabled;
        int priority;
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
    public void masksCollectionsElementWise() {
        List<Order> orders = List.of(
            new Order("O-1", "A", new Pricing(1.0, 0.1)),
            new Order("O-2", "B", new Pricing(2.0, 0.2)));

        FieldPolicyEnforcer.mask(orders, Set.of("pricing"));

        for (Order order : orders) {
            Assertions.assertNull(order.pricing);
            Assertions.assertNotNull(order.orderId);
        }
    }

    @Test
    public void masksPrimitiveFieldsToTheirDefaultValues() {
        PrimitiveRecord record = new PrimitiveRecord();
        record.enabled = true;
        record.priority = 7;

        FieldPolicyEnforcer.mask(record, Set.of("enabled", "priority"));

        Assertions.assertFalse(record.enabled);
        Assertions.assertEquals(0, record.priority);
    }

    @Test
    public void unknownReadPolicyPathFailsClosed() {
        Order order = new Order("O-1", "A", new Pricing(1.0, 0.1));

        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
            () -> FieldPolicyEnforcer.mask(order, Set.of("pricing.noSuchField")));

        Assertions.assertTrue(failure.getMessage().contains("failing closed"));
        Assertions.assertInstanceOf(NoSuchFieldException.class, failure.getCause());
    }

    @Test
    public void copyPathRestoresStoredValueOverIncomingOverwrite() throws Exception {
        Order stored = new Order("O-1", "12 Main St", new Pricing(10.5, 0.3));
        Order incoming = new Order("O-1", "12 Main St", new Pricing(999.0, 0.99));

        FieldPolicyEnforcer.copyPath(stored, incoming, "pricing.unitPrice");

        Assertions.assertEquals(10.5, incoming.pricing.unitPrice, "hidden field overwrite must be reverted");
        Assertions.assertEquals(0.99, incoming.pricing.margin, "non-protected sibling stays");
    }

    @Test
    public void unknownWritePolicyPathFailsClosed() {
        Order stored = new Order("O-1", "A", new Pricing(1.0, 0.1));
        Order incoming = new Order("O-1", "B", new Pricing(2.0, 0.2));

        Assertions.assertThrows(NoSuchFieldException.class,
            () -> FieldPolicyEnforcer.copyPath(stored, incoming, "pricing.noSuchField"));
    }

    @Test
    public void createRejectsProtectedValuesInsteadOfSilentlyClearingThem() {
        Order incoming = new Order("O-1", "12 Main St", new Pricing(10.5, 0.3));

        SecurityException failure = Assertions.assertThrows(SecurityException.class,
            () -> FieldPolicyEnforcer.assertUnset(incoming, Set.of("shippingAddress", "pricing.margin")));

        Assertions.assertTrue(failure.getMessage().contains("protected by field-level policy"));
    }

    @Test
    public void createAllowsPayloadWhenProtectedPathsAreUnset() {
        Order incoming = new Order("O-1", null, new Pricing(10.5, null));

        Assertions.assertDoesNotThrow(
            () -> FieldPolicyEnforcer.assertUnset(incoming, Set.of("shippingAddress", "pricing.margin")));
    }
}
