package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OntologyClosuresTest {

    @Test
    void testSuperProperties() {
        Map<String, PropertyDef> props = Map.of(
            "relatedTo", new PropertyDef("relatedTo", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of(), false),
            "familyRelation", new PropertyDef("familyRelation", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of("relatedTo"), false),
            "hasChild", new PropertyDef("hasChild", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of("familyRelation"), false)
        );
        
        TBox tbox = new TBox(Map.of(), props, List.of());
        OntologyRegistry registry = OntologyRegistry.inMemory(tbox);
        
        Set<String> supers = registry.superPropertiesOf("hasChild");
        assertEquals(Set.of("familyRelation", "relatedTo"), supers);
    }

    @Test
    void testSubProperties() {
        Map<String, PropertyDef> props = Map.of(
            "relatedTo", new PropertyDef("relatedTo", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of(), false),
            "familyRelation", new PropertyDef("familyRelation", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of("relatedTo"), false),
            "hasChild", new PropertyDef("hasChild", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of("familyRelation"), false)
        );
        
        TBox tbox = new TBox(Map.of(), props, List.of());
        OntologyRegistry registry = OntologyRegistry.inMemory(tbox);
        
        Set<String> subs = registry.subPropertiesOf("relatedTo");
        assertEquals(Set.of("familyRelation", "hasChild"), subs);
    }

    @Test
    void testAncestors() {
        Map<String, ClassDef> classes = Map.of(
            "Thing", new ClassDef("Thing", Set.of(), Set.of(), Set.of()),
            "Agent", new ClassDef("Agent", Set.of("Thing"), Set.of(), Set.of()),
            "Person", new ClassDef("Person", Set.of("Agent"), Set.of(), Set.of()),
            "Student", new ClassDef("Student", Set.of("Person"), Set.of(), Set.of())
        );
        
        TBox tbox = new TBox(classes, Map.of(), List.of());
        OntologyRegistry registry = OntologyRegistry.inMemory(tbox);
        
        Set<String> ancestors = registry.ancestorsOf("Student");
        assertEquals(Set.of("Person", "Agent", "Thing"), ancestors);
    }

    @Test
    void testDescendants() {
        Map<String, ClassDef> classes = Map.of(
            "Thing", new ClassDef("Thing", Set.of(), Set.of(), Set.of()),
            "Agent", new ClassDef("Agent", Set.of("Thing"), Set.of(), Set.of()),
            "Person", new ClassDef("Person", Set.of("Agent"), Set.of(), Set.of()),
            "Student", new ClassDef("Student", Set.of("Person"), Set.of(), Set.of())
        );
        
        TBox tbox = new TBox(classes, Map.of(), List.of());
        OntologyRegistry registry = OntologyRegistry.inMemory(tbox);
        
        Set<String> descendants = registry.descendantsOf("Agent");
        assertEquals(Set.of("Person", "Student"), descendants);
    }

    @Test
    void testInverseProperty() {
        Map<String, PropertyDef> props = Map.of(
            "hasChild", new PropertyDef("hasChild", Optional.empty(), Optional.empty(),
                false, Optional.of("hasParent"), false, false, false, Set.of(), false),
            "hasParent", new PropertyDef("hasParent", Optional.empty(), Optional.empty(),
                false, Optional.of("hasChild"), false, false, false, Set.of(), false)
        );
        
        TBox tbox = new TBox(Map.of(), props, List.of());
        OntologyRegistry registry = OntologyRegistry.inMemory(tbox);
        
        assertEquals(Optional.of("hasParent"), registry.inverseOf("hasChild"));
        assertEquals(Optional.of("hasChild"), registry.inverseOf("hasParent"));
    }

    @Test
    void testInversePropertyReverseLookup() {
        // Only hasChild declares inverse, hasParent should be found via reverse lookup
        Map<String, PropertyDef> props = Map.of(
            "hasChild", new PropertyDef("hasChild", Optional.empty(), Optional.empty(),
                false, Optional.of("hasParent"), false, false, false, Set.of(), false),
            "hasParent", new PropertyDef("hasParent", Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, false, false, Set.of(), false)
        );
        
        TBox tbox = new TBox(Map.of(), props, List.of());
        OntologyRegistry registry = OntologyRegistry.inMemory(tbox);
        
        assertEquals(Optional.of("hasParent"), registry.inverseOf("hasChild"));
        assertEquals(Optional.of("hasChild"), registry.inverseOf("hasParent"));
    }
}
