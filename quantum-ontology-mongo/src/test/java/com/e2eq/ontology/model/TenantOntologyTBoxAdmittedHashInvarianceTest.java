package com.e2eq.ontology.model;

import com.e2eq.ontology.core.CanonicalTBoxHasher;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * B5 SLICE 1 — HASH INVARIANCE (the load-bearing test).
 *
 * <p>The realm-governance {@code admittedPredicates} field on
 * {@link TenantOntologyTBox} is out-of-band from pack identity: it MUST NOT
 * reach {@link CanonicalTBoxHasher}. Two docs identical except for
 * {@code admittedPredicates} must produce the same canonical TBox hash, because
 * {@code toTBox()} does not surface that field.</p>
 */
public class TenantOntologyTBoxAdmittedHashInvarianceTest {

    private static final CanonicalTBoxHasher.PackMetadata META =
            new CanonicalTBoxHasher.PackMetadata("realm-pack", "Realm Pack", 1, "closed", null, null);

    private static TenantOntologyTBox docWith(Set<String> admitted) {
        Map<String, ClassDef> classes = Map.of(
                "Associate", new ClassDef("Associate", Set.of(), Set.of(), Set.of()),
                "Location", new ClassDef("Location", Set.of(), Set.of(), Set.of()));
        Map<String, PropertyDef> properties = Map.of(
                "canSeeLocation", new PropertyDef("canSeeLocation",
                        Optional.of("Associate"), Optional.of("Location"),
                        false, Optional.empty(), false, false, false, Set.of(), false),
                "ownsTerritory", new PropertyDef("ownsTerritory",
                        Optional.empty(), Optional.empty(),
                        false, Optional.empty(), false, false, false, Set.of(), false));
        TBox tbox = new TBox(classes, properties, List.of());
        TenantOntologyTBox doc = new TenantOntologyTBox(tbox, "h", "y", "submit", "1.0.0");
        doc.setAdmittedPredicates(admitted);
        return doc;
    }

    @Test
    void admittedPredicatesDoesNotChangeCanonicalHash() {
        TenantOntologyTBox legacy = docWith(null);                              // null == all admitted
        TenantOntologyTBox partial = docWith(Set.of("canSeeLocation"));         // one provisional
        TenantOntologyTBox full = docWith(Set.of("canSeeLocation", "ownsTerritory"));
        TenantOntologyTBox empty = docWith(Set.of());                          // all provisional

        String legacyHash = CanonicalTBoxHasher.hashTBox(legacy.toTBox(), META);
        String partialHash = CanonicalTBoxHasher.hashTBox(partial.toTBox(), META);
        String fullHash = CanonicalTBoxHasher.hashTBox(full.toTBox(), META);
        String emptyHash = CanonicalTBoxHasher.hashTBox(empty.toTBox(), META);

        assertNotNull(legacyHash);
        assertEquals(legacyHash, partialHash,
                "setting admittedPredicates must NOT change the canonical TBox hash");
        assertEquals(legacyHash, fullHash,
                "a different admittedPredicates value must NOT change the canonical TBox hash");
        assertEquals(legacyHash, emptyHash,
                "an empty admittedPredicates set must NOT change the canonical TBox hash");
    }

    @Test
    void toTBoxDoesNotCarryAdmittedPredicates() {
        // The realm-governance field is invisible to the TBox record entirely:
        // toTBox() only exposes classes/properties/chains. (No admitted accessor exists
        // on TBox, so this is verified by the hash-invariance assertion above plus the
        // structural equality of the produced TBoxes.)
        TenantOntologyTBox a = docWith(Set.of("canSeeLocation"));
        TenantOntologyTBox b = docWith(Set.of("ownsTerritory"));
        assertEquals(a.toTBox().properties().keySet(), b.toTBox().properties().keySet());
        assertEquals(a.toTBox().classes().keySet(), b.toTBox().classes().keySet());
    }
}
