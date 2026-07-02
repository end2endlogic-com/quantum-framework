package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OntologyReferenceCloserTest {

    private static PropertyDef prop(String name, String domain, String range) {
        return new PropertyDef(name, Optional.ofNullable(domain), Optional.ofNullable(range),
                false, Optional.empty(), false, false, false, Set.of(), false);
    }

    @Test
    void materializesStubForCrossModuleRange() {
        // ApplicationPackVersion.usesOntology -> Ontology, where Ontology is owned by another pack
        // and is therefore not declared in this (annotation-scanned) TBox.
        Map<String, ClassDef> classes = Map.of(
                "ApplicationPackVersion", new ClassDef("ApplicationPackVersion", Set.of(), Set.of(), Set.of()));
        Map<String, PropertyDef> props = Map.of(
                "usesOntology", prop("usesOntology", "ApplicationPackVersion", "Ontology"));
        TBox in = new TBox(classes, props, List.of());

        TBox out = OntologyReferenceCloser.closeClassReferences(in);

        assertTrue(out.classes().containsKey("Ontology"), "external range class should be stubbed");
        assertTrue(out.classes().containsKey("ApplicationPackVersion"), "real class must survive");
        assertEquals(Boolean.TRUE,
                out.classes().get("Ontology").metadata().get(OntologyReferenceCloser.EXTERNAL_METADATA_KEY),
                "stub must be tagged external for provenance");
    }

    @Test
    void closesDanglingDomainToo() {
        TBox in = new TBox(Map.of(), Map.of("p", prop("p", "MissingDomain", null)), List.of());
        TBox out = OntologyReferenceCloser.closeClassReferences(in);
        assertTrue(out.classes().containsKey("MissingDomain"));
    }

    @Test
    void returnsSameInstanceWhenNothingDangling() {
        Map<String, ClassDef> classes = Map.of(
                "Person", new ClassDef("Person", Set.of(), Set.of(), Set.of()));
        Map<String, PropertyDef> props = Map.of("knows", prop("knows", "Person", "Person"));
        TBox in = new TBox(classes, props, List.of());

        assertSame(in, OntologyReferenceCloser.closeClassReferences(in),
                "no dangling refs -> unchanged, no needless copy");
    }

    @Test
    void closedTBoxPassesMergeValidationThatWouldOtherwiseThrow() {
        Map<String, ClassDef> classes = Map.of(
                "ApplicationPackVersion", new ClassDef("ApplicationPackVersion", Set.of(), Set.of(), Set.of()));
        Map<String, PropertyDef> props = Map.of(
                "usesOntology", prop("usesOntology", "ApplicationPackVersion", "Ontology"),
                "usesSeedPack", prop("usesSeedPack", "ApplicationPackVersion", "SeedPack"));
        TBox raw = new TBox(classes, props, List.of());

        // Reproduces the defect: merging the raw (dangling) TBox invokes OntologyValidator and throws.
        TBox empty = new TBox(Map.of(), Map.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> OntologyMerger.merge(empty, raw));

        // Closing references first makes the merge validate cleanly and keep the real class.
        TBox closed = OntologyReferenceCloser.closeClassReferences(raw);
        TBox merged = assertDoesNotThrow(() -> OntologyMerger.merge(empty, closed));
        assertTrue(merged.classes().containsKey("ApplicationPackVersion"));
        assertTrue(merged.classes().containsKey("Ontology"));
        assertTrue(merged.classes().containsKey("SeedPack"));
    }

    @Test
    void doesNotClosePropertyToPropertyReferences() {
        // inverseOf targets a PROPERTY, not a class: this is a local integrity constraint that must
        // stay strict. The closer must not silence it.
        Map<String, ClassDef> classes = Map.of(
                "Person", new ClassDef("Person", Set.of(), Set.of(), Set.of()));
        PropertyDef withBadInverse = new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"),
                true, Optional.of("missingInverseProp"), false, false, false, Set.of(), false);
        TBox in = new TBox(classes, Map.of("knows", withBadInverse), List.of());

        TBox out = OntologyReferenceCloser.closeClassReferences(in);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OntologyValidator.validate(out));
        assertTrue(ex.getMessage().contains("Unknown property 'missingInverseProp'"));
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(OntologyReferenceCloser.closeClassReferences(null));
    }
}
