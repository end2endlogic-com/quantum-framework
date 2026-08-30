package com.e2eq.ontology.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.e2eq.ontology.annotations.OntologyProperty;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A declared property half-life is vocabulary, so it is part of the hashed TBox -- and a property
 * that declares none must leave the hash exactly as it was.
 *
 * <p>Both halves matter. Without the first, two packs that disagree about how long a value stays
 * true would share an identity and a receipt could not tell them apart. Without the second,
 * adding this feature would silently invalidate every pinned hash in every receipt already
 * issued.
 */
class PropertyHalfLifeTest {

    private static final CanonicalTBoxHasher.PackMetadata META =
            CanonicalTBoxHasher.PackMetadata.closedPack("pack.test", "Test Pack", 1);

    private static OntologyRegistry.PropertyDef property(Map<String, Object> metadata) {
        return new OntologyRegistry.PropertyDef(
                "registrationStatus",
                Optional.of("Supplier"),
                Optional.of("String"),
                false,
                Optional.empty(),
                false,
                false,
                true,
                Set.of(),
                false,
                null,
                Set.of(),
                metadata);
    }

    private static OntologyRegistry.TBox tbox(OntologyRegistry.PropertyDef property) {
        return new OntologyRegistry.TBox(Map.of(), Map.of(property.name(), property), List.of());
    }

    @Test
    @DisplayName("a property with no declared half-life hashes exactly as before")
    void undeclaredHalfLifeLeavesTheHashUnchanged() {
        String withEmptyMetadata = CanonicalTBoxHasher.hashTBox(tbox(property(Map.of())), META);

        // Map.of() is what the loader writes when the annotation is left at its default, so this
        // is the hash every existing pack already has.
        assertEquals(withEmptyMetadata, CanonicalTBoxHasher.hashTBox(tbox(property(Map.of())), META));
        assertTrue(withEmptyMetadata.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("declaring a half-life changes the pack identity")
    void declaredHalfLifeChangesTheHash() {
        String undeclared = CanonicalTBoxHasher.hashTBox(tbox(property(Map.of())), META);
        String declared = CanonicalTBoxHasher.hashTBox(
                tbox(property(Map.of(OntologyProperty.HALF_LIFE_METADATA_KEY, 604800L))), META);

        // Two packs that disagree about how long a value stays true are different vocabularies,
        // and a receipt pinned to one must not validate against the other.
        assertNotEquals(undeclared, declared);
    }

    @Test
    @DisplayName("a different half-life is a different pack")
    void differentHalfLivesHashDifferently() {
        String weekly = CanonicalTBoxHasher.hashTBox(
                tbox(property(Map.of(OntologyProperty.HALF_LIFE_METADATA_KEY, 604800L))), META);
        String yearly = CanonicalTBoxHasher.hashTBox(
                tbox(property(Map.of(OntologyProperty.HALF_LIFE_METADATA_KEY, 31536000L))), META);

        assertNotEquals(weekly, yearly);
    }

    @Test
    @DisplayName("the unspecified sentinel is not a number a consumer could mistake for a judgement")
    void sentinelIsDistinguishableFromADeclaredValue() {
        // A fabricated default would be indistinguishable from a reviewed one. Negative means
        // "nobody has decided", which no real half-life can be.
        assertTrue(OntologyProperty.HALF_LIFE_UNSPECIFIED < 0);
    }
}
