package com.e2eq.ontology.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.e2eq.ontology.core.StableRelationDiffTest.prop;
import static com.e2eq.ontology.core.StableRelationDiffTest.tbox;
import static org.junit.jupiter.api.Assertions.*;

class SourceTBoxGateTest {

    private static final OntologyRegistry.TBox WITH_STABLE =
            tbox(prop("hasShipment", "Customer", "Shipment", true, RelationStability.STABLE));
    private static final OntologyRegistry.TBox WITHOUT = tbox();

    @Test
    void removingAStableRelationWithoutABumpIsRefused() {
        SourceTBoxGate.Outcome outcome = SourceTBoxGate.check(WITH_STABLE, 3, WITHOUT, 3, WITHOUT);
        assertFalse(outcome.admissible());
        assertEquals(List.of(StableRelationDiff.Code.STABLE_RELATION_REMOVED),
                outcome.violations().stream().map(StableRelationDiff.Violation::code).toList());
    }

    @Test
    void aPackVersionBumpPublishesTheChange() {
        SourceTBoxGate.Outcome outcome = SourceTBoxGate.check(WITH_STABLE, 3, WITHOUT, 4, WITHOUT);
        assertTrue(outcome.admissible());
        assertEquals(1, outcome.violations().size());
    }

    @Test
    void anUnversionedBaselineCannotShowABump() {
        assertFalse(SourceTBoxGate.check(WITH_STABLE, null, WITHOUT, 7, WITHOUT).admissible());
        assertTrue(SourceTBoxGate.check(WITH_STABLE, null, WITH_STABLE, 0, WITH_STABLE).admissible());
    }

    @Test
    void aFirstBuildHasNothingToBreak() {
        SourceTBoxGate.Outcome outcome = SourceTBoxGate.check(null, null, WITH_STABLE, 0, WITH_STABLE);
        assertTrue(outcome.admissible());
        assertTrue(outcome.violations().isEmpty());
    }

    @Test
    void stableOnlyThroughAnAnnotationIsReported() {
        OntologyRegistry.TBox packInternal = tbox(prop("hasShipment", "Customer", "Shipment", true, RelationStability.INTERNAL));
        assertEquals(List.of("hasShipment"), SourceTBoxGate.stableOutsidePack(WITH_STABLE, packInternal));
        assertEquals(List.of("hasShipment"), SourceTBoxGate.stableOutsidePack(WITH_STABLE, null));
        assertEquals(List.of(), SourceTBoxGate.stableOutsidePack(WITH_STABLE, WITH_STABLE));
    }

    @Test
    void onlyWarnRelaxesTheGate() {
        assertEquals(SourceTBoxGate.Mode.WARN, SourceTBoxGate.Mode.parse(" Warn "));
        assertEquals(SourceTBoxGate.Mode.ENFORCE, SourceTBoxGate.Mode.parse("enforce"));
        assertEquals(SourceTBoxGate.Mode.ENFORCE, SourceTBoxGate.Mode.parse("off"));
        assertEquals(SourceTBoxGate.Mode.ENFORCE, SourceTBoxGate.Mode.parse(null));
    }

    @Test
    void theRefusalNamesTheCodeTheRelationAndTheFix() {
        SourceTBoxGate.Outcome outcome = SourceTBoxGate.check(WITH_STABLE, null, WITHOUT, 1, WITHOUT);
        String message = new SourceTBoxGate.StableContractException("acme", outcome.violations(), null, 1,
                List.of("Morphia entities: boom")).getMessage();
        assertTrue(message.contains("stable_relation_removed: Stable relation 'hasShipment' was removed"), message);
        assertTrue(message.contains("last built unversioned"), message);
        assertTrue(message.contains("Morphia entities: boom"), message);
        assertTrue(message.contains("raise the pack version"), message);
    }
}
