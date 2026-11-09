package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OntologyValidatorTest {

    @Test
    void testValidOntology() {
        Map<String, ClassDef> classes = Map.of(
            "Person", new ClassDef("Person", Set.of(), Set.of(), Set.of())
        );
        
        Map<String, PropertyDef> props = Map.of(
            "knows", new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"),
                false, Optional.empty(), false, true, false, Set.of(), false)
        );
        
        TBox tbox = new TBox(classes, props, List.of());
        assertDoesNotThrow(() -> OntologyValidator.validate(tbox));
    }

    @Test
    void testUnknownClassInDomain() {
        Map<String, ClassDef> classes = Map.of();
        Map<String, PropertyDef> props = Map.of(
            "knows", new PropertyDef("knows", Optional.of("Person"), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of(), false)
        );
        
        TBox tbox = new TBox(classes, props, List.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OntologyValidator.validate(tbox));
        assertTrue(ex.getMessage().contains("Unknown class 'Person'"));
    }

    @Test
    void testCycleInPropertyHierarchy() {
        Map<String, ClassDef> classes = Map.of();
        Map<String, PropertyDef> props = Map.of(
            "p1", new PropertyDef("p1", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of("p2"), false),
            "p2", new PropertyDef("p2", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of("p1"), false)
        );
        
        TBox tbox = new TBox(classes, props, List.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OntologyValidator.validate(tbox));
        assertTrue(ex.getMessage().contains("Cycle detected"));
    }

    @Test
    void testTransitivePropertyWithDifferentDomainRange() {
        Map<String, ClassDef> classes = Map.of(
            "Person", new ClassDef("Person", Set.of(), Set.of(), Set.of()),
            "Org", new ClassDef("Org", Set.of(), Set.of(), Set.of())
        );
        Map<String, PropertyDef> props = Map.of(
            "bad", new PropertyDef("bad", Optional.of("Person"), Optional.of("Org"),
                false, Optional.empty(), true, false, false, Set.of(), false)
        );
        
        TBox tbox = new TBox(classes, props, List.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OntologyValidator.validate(tbox));
        assertTrue(ex.getMessage().contains("must have same domain and range"));
    }

    @Test
    void testPropertyChainWithTransitiveProperty() {
        Map<String, ClassDef> classes = Map.of(
            "Person", new ClassDef("Person", Set.of(), Set.of(), Set.of())
        );
        Map<String, PropertyDef> props = Map.of(
            "ancestorOf", new PropertyDef("ancestorOf", Optional.of("Person"), Optional.of("Person"),
                false, Optional.empty(), true, false, false, Set.of(), false),
            "knows", new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"),
                false, Optional.empty(), false, false, false, Set.of(), false),
            "related", new PropertyDef("related", Optional.of("Person"), Optional.of("Person"),
                false, Optional.empty(), false, false, false, Set.of(), false)
        );
        
        PropertyChainDef chain = new PropertyChainDef(List.of("ancestorOf", "knows"), "related");
        TBox tbox = new TBox(classes, props, List.of(chain));
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OntologyValidator.validate(tbox));
        assertTrue(ex.getMessage().contains("non-regular"));
    }

    @Test
    void testPropertyChainTooShort() {
        Map<String, ClassDef> classes = Map.of();
        Map<String, PropertyDef> props = Map.of(
            "p1", new PropertyDef("p1", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of(), false),
            "p2", new PropertyDef("p2", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of(), false)
        );
        
        PropertyChainDef chain = new PropertyChainDef(List.of("p1"), "p2");
        TBox tbox = new TBox(classes, props, List.of(chain));
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OntologyValidator.validate(tbox));
        assertTrue(ex.getMessage().contains("at least 2 properties"));
    }

    private static TBox shipmentChainTBox(String firstDomain, String lastRange) {
        Map<String, ClassDef> classes = new LinkedHashMap<>();
        classes.put("Party", new ClassDef("Party", Set.of(), Set.of(), Set.of()));
        classes.put("Customer", new ClassDef("Customer", Set.of("Party"), Set.of(), Set.of()));
        classes.put("PurchaseOrder", new ClassDef("PurchaseOrder", Set.of(), Set.of(), Set.of()));
        classes.put("PurchaseOrderLine", new ClassDef("PurchaseOrderLine", Set.of(), Set.of(), Set.of()));
        classes.put("Shipment", new ClassDef("Shipment", Set.of(), Set.of(), Set.of()));
        classes.put("Parcel", new ClassDef("Parcel", Set.of("Shipment"), Set.of(), Set.of()));
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("hasPO", new PropertyDef("hasPO", Optional.of(firstDomain), Optional.of("PurchaseOrder"),
            false, Optional.empty(), false, false, false, Set.of(), false));
        props.put("hasLine", new PropertyDef("hasLine", Optional.of("PurchaseOrder"), Optional.of("PurchaseOrderLine"),
            false, Optional.empty(), false, false, false, Set.of(), false));
        props.put("lineShipment", new PropertyDef("lineShipment", Optional.of("PurchaseOrderLine"), Optional.of(lastRange),
            false, Optional.empty(), false, false, false, Set.of(), false));
        props.put("hasShipment", new PropertyDef("hasShipment", Optional.of("Customer"), Optional.of("Shipment"),
            false, Optional.empty(), false, false, false, Set.of(), true));
        PropertyChainDef chain = new PropertyChainDef(List.of("hasPO", "hasLine", "lineShipment"), "hasShipment");
        return new TBox(classes, props, List.of(chain));
    }

    @Test
    void testChainWhoseEndpointsAreSubclassesOfTheImpliedTypesIsValid() {
        assertDoesNotThrow(() -> OntologyValidator.validate(shipmentChainTBox("Customer", "Parcel")));
    }

    @Test
    void testChainStartingOutsideTheImpliedDomainIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OntologyValidator.validate(shipmentChainTBox("Party", "Shipment")));
        assertTrue(ex.getMessage().contains("first step 'hasPO' domain 'Party'"), ex.getMessage());
    }

    @Test
    void testChainEndingOutsideTheImpliedRangeIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> OntologyValidator.validate(shipmentChainTBox("Customer", "PurchaseOrder")));
        assertTrue(ex.getMessage().contains("last step 'lineShipment' range 'PurchaseOrder'"), ex.getMessage());
    }

    @Test
    void testChainEndpointOwnedByAnotherPackIsNotTypeChecked() {
        // Party is referenced but declared by another pack; the source build stubs it before validating.
        TBox tbox = OntologyReferenceCloser.closeClassReferences(withoutClass(shipmentChainTBox("Party", "Shipment"), "Party"));

        assertDoesNotThrow(() -> OntologyValidator.validate(tbox));
    }

    @Test
    void testChainMemberWithoutADeclaredDomainIsNotTypeChecked() {
        TBox base = shipmentChainTBox("Customer", "Shipment");
        Map<String, PropertyDef> props = new LinkedHashMap<>(base.properties());
        props.put("hasPO", new PropertyDef("hasPO", Optional.empty(), Optional.of("PurchaseOrder"),
            false, Optional.empty(), false, false, false, Set.of(), false));

        assertDoesNotThrow(() -> OntologyValidator.validate(new TBox(base.classes(), props, base.propertyChains())));
    }

    private static TBox withoutClass(TBox tbox, String classId) {
        Map<String, ClassDef> classes = new LinkedHashMap<>(tbox.classes());
        classes.remove(classId);
        Map<String, ClassDef> reparented = new LinkedHashMap<>();
        classes.forEach((id, c) -> reparented.put(id, c.parents().contains(classId)
            ? new ClassDef(id, Set.of(), c.disjointWith(), c.sameAs()) : c));
        return new TBox(reparented, tbox.properties(), tbox.propertyChains());
    }
}
