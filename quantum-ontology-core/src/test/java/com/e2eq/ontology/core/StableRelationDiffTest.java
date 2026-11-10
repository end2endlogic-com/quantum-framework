package com.e2eq.ontology.core;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs {@code stable-relation-diff.json}, shared with the Python implementation, plus edge cases. */
class StableRelationDiffTest {

    @SuppressWarnings("unchecked")
    @TestFactory
    List<DynamicTest> vectorCases() throws Exception {
        Map<String, Object> vector = VectorPacks.readVector("stable-relation-diff");
        Map<String, Object> activePack = (Map<String, Object>) vector.get("active");
        OntologyRegistry.TBox active = VectorPacks.tboxFromPack(activePack);
        int activeVersion = ((Number) activePack.get("version")).intValue();
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) vector.get("cases")) {
            tests.add(DynamicTest.dynamicTest((String) c.get("name"), () -> {
                Map<String, Object> candidatePack = (Map<String, Object>) c.get("candidate");
                Map<String, Object> expected = (Map<String, Object>) c.get("expected");
                StableRelationDiff.Result result = StableRelationDiff.diff(active, activeVersion,
                        VectorPacks.tboxFromPack(candidatePack), ((Number) candidatePack.get("version")).intValue());
                assertEquals(expected.get("violations"), asMaps(result.violations()));
                assertEquals(expected.get("admissible"), result.admissible());
            }));
        }
        return tests;
    }

    @Test
    void oneRelationCanReportEveryChangeInTableOrder() {
        OntologyRegistry.TBox active = tbox(prop("hasShipment", "Customer", "Shipment", true, RelationStability.STABLE));
        OntologyRegistry.TBox candidate = tbox(prop("hasShipment", "PremiumCustomer", "Parcel", false, RelationStability.INTERNAL));
        List<StableRelationDiff.Violation> violations = StableRelationDiff.violations(active, candidate);
        assertEquals(List.of(
                StableRelationDiff.Code.STABLE_RELATION_DEMOTED,
                StableRelationDiff.Code.STABLE_RELATION_DOMAIN_NARROWED,
                StableRelationDiff.Code.STABLE_RELATION_RANGE_NARROWED,
                StableRelationDiff.Code.STABLE_RELATION_DERIVATION_CHANGED),
                violations.stream().map(StableRelationDiff.Violation::code).toList());
        assertEquals("Stable relation 'hasShipment' range narrowed from 'Shipment' to 'Parcel'", violations.get(2).message());
        assertEquals("Stable relation 'hasShipment' derivation changed from 'derived' to 'asserted'", violations.get(3).message());
    }

    @Test
    void violationsAreInRelationNameOrder() {
        OntologyRegistry.TBox active = tbox(
                prop("zeta", "Customer", "Shipment", false, RelationStability.STABLE),
                prop("alpha", "Customer", "Shipment", false, RelationStability.STABLE));
        OntologyRegistry.TBox candidate = tbox();
        assertEquals(List.of("alpha", "zeta"),
                StableRelationDiff.violations(active, candidate).stream().map(StableRelationDiff.Violation::relation).toList());
    }

    @Test
    void activeTypeTheCandidateNoLongerDeclaresIsNarrowed() {
        OntologyRegistry.TBox active = tbox(prop("hasShipment", "Customer", "Shipment", false, RelationStability.STABLE));
        Map<String, OntologyRegistry.ClassDef> classes = new LinkedHashMap<>(classes());
        classes.remove("Customer");
        OntologyRegistry.TBox candidate = new OntologyRegistry.TBox(classes,
                Map.of("hasShipment", prop("hasShipment", "Customer", "Shipment", false, RelationStability.STABLE)), List.of());
        assertEquals(List.of(StableRelationDiff.Code.STABLE_RELATION_DOMAIN_NARROWED),
                StableRelationDiff.violations(active, candidate).stream().map(StableRelationDiff.Violation::code).toList());
    }

    @Test
    void sameAsCountsAsWideningInBothDirectionsAndMissingCandidateTypeNeverNarrows() {
        Map<String, OntologyRegistry.ClassDef> classes = new LinkedHashMap<>(classes());
        classes.put("Client", new OntologyRegistry.ClassDef("Client", Set.of(), Set.of(), Set.of("Customer")));
        OntologyRegistry.TBox active = tbox(prop("hasShipment", "Customer", "Shipment", false, RelationStability.STABLE));
        OntologyRegistry.TBox candidate = new OntologyRegistry.TBox(classes, Map.of("hasShipment",
                new OntologyRegistry.PropertyDef("hasShipment", Optional.of("Client"), Optional.empty(), false,
                        Optional.empty(), false, false, false, Set.of(), false, null, Set.of(), Map.of(),
                        RelationStability.STABLE)), List.of());
        assertTrue(StableRelationDiff.violations(active, candidate).isEmpty());
    }

    @Test
    void internalRelationsAreNotCompared() {
        OntologyRegistry.TBox active = tbox(prop("hasShipment", "Customer", "Shipment", true, RelationStability.INTERNAL));
        assertTrue(StableRelationDiff.violations(active, tbox()).isEmpty());
        assertFalse(StableRelationDiff.admissible(
                StableRelationDiff.violations(tbox(prop("r", "Customer", "Shipment", false, RelationStability.STABLE)), tbox()),
                3, 3));
    }

    private static List<Map<String, Object>> asMaps(List<StableRelationDiff.Violation> violations) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StableRelationDiff.Violation v : violations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("relation", v.relation());
            m.put("code", v.code().wireValue());
            m.put("message", v.message());
            out.add(m);
        }
        return out;
    }

    static Map<String, OntologyRegistry.ClassDef> classes() {
        Map<String, OntologyRegistry.ClassDef> classes = new LinkedHashMap<>();
        classes.put("Customer", new OntologyRegistry.ClassDef("Customer", Set.of(), Set.of(), Set.of()));
        classes.put("PremiumCustomer", new OntologyRegistry.ClassDef("PremiumCustomer", Set.of("Customer"), Set.of(), Set.of()));
        classes.put("Shipment", new OntologyRegistry.ClassDef("Shipment", Set.of(), Set.of(), Set.of()));
        classes.put("Parcel", new OntologyRegistry.ClassDef("Parcel", Set.of("Shipment"), Set.of(), Set.of()));
        return classes;
    }

    static OntologyRegistry.TBox tbox(OntologyRegistry.PropertyDef... props) {
        Map<String, OntologyRegistry.PropertyDef> map = new LinkedHashMap<>();
        for (OntologyRegistry.PropertyDef p : props) map.put(p.name(), p);
        return new OntologyRegistry.TBox(classes(), map, List.of());
    }

    static OntologyRegistry.PropertyDef prop(String name, String domain, String range, boolean inferred,
                                             RelationStability stability) {
        return new OntologyRegistry.PropertyDef(name, Optional.ofNullable(domain), Optional.ofNullable(range),
                false, Optional.empty(), false, false, false, Set.of(), inferred, null, Set.of(), Map.of(), stability);
    }
}
