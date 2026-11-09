package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OntologyMergerTest {

    @Test
    void testMergingAPropertyDeclaredOnBothSidesKeepsLabelAliasesAndMetadata() {
        ClassDef customer = new ClassDef("Customer", Set.of(), Set.of(), Set.of());
        ClassDef shipment = new ClassDef("Shipment", Set.of(), Set.of(), Set.of());
        PropertyDef base = new PropertyDef("hasShipment", Optional.of("Customer"), Optional.of("Shipment"),
            false, Optional.empty(), false, false, false, Set.of(), false,
            "Has shipment", Set.of("shipments"), Map.of("joinFromField", "id", "joinToField", "customerId"));
        PropertyDef overlay = new PropertyDef("hasShipment", Optional.empty(), Optional.empty(),
            false, Optional.empty(), false, false, false, Set.of(), true,
            null, Set.of("deliveries"), Map.of("joinToField", "customerRef"));

        TBox merged = OntologyMerger.merge(
            new TBox(Map.of("Customer", customer, "Shipment", shipment), Map.of("hasShipment", base), List.of()),
            new TBox(Map.of(), Map.of("hasShipment", overlay), List.of()));

        PropertyDef result = merged.properties().get("hasShipment");
        assertEquals("Has shipment", result.label());
        assertEquals(Set.of("shipments", "deliveries"), result.aliases());
        assertEquals(Map.of("joinFromField", "id", "joinToField", "customerRef"), result.metadata());
        assertEquals(Optional.of("Customer"), result.domain());
        assertTrue(result.inferred());
    }

    @Test
    void testMergingAClassDeclaredOnBothSidesKeepsLabelAliasesAndMetadata() {
        ClassDef base = new ClassDef("Customer", Set.of(), Set.of(), Set.of(),
            "Customer", Set.of("client"), Map.of("resourceContext", "customers"));
        ClassDef overlay = new ClassDef("Customer", Set.of(), Set.of(), Set.of(),
            "Account holder", Set.of("buyer"), Map.of("area", "sales"));

        TBox merged = OntologyMerger.merge(
            new TBox(Map.of("Customer", base), Map.of(), List.of()),
            new TBox(Map.of("Customer", overlay), Map.of(), List.of()));

        ClassDef result = merged.classes().get("Customer");
        assertEquals("Account holder", result.label());
        assertEquals(Set.of("client", "buyer"), result.aliases());
        assertEquals(Map.of("resourceContext", "customers", "area", "sales"), result.metadata());
    }

    @Test
    void testExternalStubNeverOverridesARealClassInEitherOrder() {
        ClassDef real = new ClassDef("Ontology", Set.of(), Set.of(), Set.of(),
            "Ontology Pack", Set.of("pack"), Map.of("resourceContext", "ontologies"));
        ClassDef stub = new ClassDef("Ontology", Set.of(), Set.of(), Set.of(),
            "Ontology", Set.of(), Map.of(OntologyReferenceCloser.EXTERNAL_METADATA_KEY, Boolean.TRUE));

        for (TBox[] order : List.of(
                new TBox[] {tboxWith(real), tboxWith(stub)},
                new TBox[] {tboxWith(stub), tboxWith(real)})) {
            ClassDef result = OntologyMerger.merge(order[0], order[1]).classes().get("Ontology");
            assertEquals("Ontology Pack", result.label());
            assertEquals(Map.of("resourceContext", "ontologies"), result.metadata());
            assertFalse(OntologyReferenceCloser.isExternalStub(result));
        }
    }

    private static TBox tboxWith(ClassDef c) {
        return new TBox(Map.of(c.name(), c), Map.of(), List.of());
    }
}
