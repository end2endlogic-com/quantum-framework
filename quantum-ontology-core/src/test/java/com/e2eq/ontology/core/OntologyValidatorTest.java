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
}
