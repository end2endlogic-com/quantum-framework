package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ontology relation contract (P1): fields, hash, validation, explicit edges, guard and merge. */
class StableRelationContractTest {

    // ---- fields ---------------------------------------------------------------------------------

    @Test
    void stabilityParsesCaseInsensitivelyAndDefaultsToInternal() {
        assertEquals(RelationStability.STABLE, RelationStability.parse("p", "Stable"));
        assertEquals(RelationStability.INTERNAL, RelationStability.parse("p", "INTERNAL"));
        assertEquals(RelationStability.INTERNAL, RelationStability.parse("p", null));
        assertEquals(RelationStability.INTERNAL, RelationStability.parse("p", " "));
    }

    @Test
    void unknownStabilityIsAnErrorQuotingTheNormalisedValue() {
        assertMessage("Property 'hasShipment' has unknown stability 'frozen': use stable or internal",
                () -> RelationStability.parse("hasShipment", " Frozen "));
    }

    @Test
    void unknownDerivationIsAnErrorQuotingTheNormalisedValue() {
        assertMessage("Property 'hasShipment' has unknown derivation 'computed': use asserted or derived",
                () -> RelationDerivation.parse("hasShipment", " Computed "));
    }

    @Test
    void enumsReadTheirLowercaseJsonForm() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("\"stable\"", mapper.writeValueAsString(RelationStability.STABLE));
        assertEquals("\"derived\"", mapper.writeValueAsString(RelationDerivation.DERIVED));
        assertEquals(RelationStability.STABLE, mapper.readValue("\"Stable\"", RelationStability.class));
        assertEquals(RelationDerivation.ASSERTED, mapper.readValue("\"asserted\"", RelationDerivation.class));
    }

    @Test
    void legacyHashChangesOnlyForStableRelations() {
        TBox internal = new TBox(classes("A"), props(prop("p", "A", "A", false, RelationStability.INTERNAL)), List.of());
        TBox stable = new TBox(classes("A"), props(prop("p", "A", "A", false, RelationStability.STABLE)), List.of());
        TBox legacy = new TBox(classes("A"), props(new PropertyDef("p", Optional.of("A"), Optional.of("A"), false,
                Optional.empty(), false, false, false, Set.of())), List.of());
        assertEquals(TBoxHasher.computeHash(legacy), TBoxHasher.computeHash(internal));
        assertFalse(TBoxHasher.computeHash(internal).equals(TBoxHasher.computeHash(stable)));
    }

    @Test
    void derivationAndInferredMustAgree() {
        assertMessage("Property 'hasShipment' declares derivation 'derived' but inferred is false",
                () -> RelationDerivation.resolveInferred("hasShipment", "derived", false));
        assertMessage("Property 'hasShipment' declares derivation 'asserted' but inferred is true",
                () -> RelationDerivation.resolveInferred("hasShipment", "asserted", true));
        assertTrue(RelationDerivation.resolveInferred("p", "DERIVED", null));
        assertTrue(RelationDerivation.resolveInferred("p", "derived", true));
        assertFalse(RelationDerivation.resolveInferred("p", null, null));
        // Where a conflict is reported separately, an explicit inferred wins
        assertFalse(RelationDerivation.inferredBit("p", "derived", false));
    }

    @Test
    void propertyDefKeepsOlderConstructorsAsInternal() {
        PropertyDef thirteen = new PropertyDef("p", Optional.empty(), Optional.empty(), false, Optional.empty(),
                false, false, false, Set.of(), true, null, Set.of(), Map.of());
        assertEquals(RelationStability.INTERNAL, thirteen.stability());
        assertEquals(RelationDerivation.DERIVED, thirteen.derivation());
        assertFalse(thirteen.isStable());
        PropertyDef stable = thirteen.withStability(RelationStability.STABLE);
        assertTrue(stable.isStable());
        assertTrue(stable.isStableDerived());
        assertEquals(RelationStability.INTERNAL, new PropertyDef("p", Optional.empty(), Optional.empty(), false,
                Optional.empty(), false, false, false, Set.of()).stability());
    }

    // ---- canonical hash -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void bothGoldenHashesMatchThePythonReference() throws Exception {
        for (String name : List.of("hash-golden", "hash-golden-stability")) {
            Map<String, Object> vector = VectorPacks.readVector(name);
            Map<String, Object> pack = (Map<String, Object>) vector.get("tbox");
            assertEquals(((Map<String, Object>) vector.get("expected")).get("tbox_hash"),
                    CanonicalTBoxHasher.hashPackMapping(pack), name);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void recordPathHashesTheStabilityGoldenIdentically() throws Exception {
        Map<String, Object> vector = VectorPacks.readVector("hash-golden-stability");
        Map<String, Object> pack = (Map<String, Object>) vector.get("tbox");
        TBox tbox = VectorPacks.tboxFromPack(pack);
        // Labels and aliases live outside VectorPacks; restore Person's so the records match the pack
        Map<String, ClassDef> classes = new LinkedHashMap<>(tbox.classes());
        classes.put("Person", new ClassDef("Person", Set.of(), Set.of(), Set.of(), "Person", Set.of("human"), Map.of()));
        TBox records = new TBox(classes, tbox.properties(), tbox.propertyChains());
        OntologyValidator.validate(records);
        String hash = CanonicalTBoxHasher.hashTBox(records, new CanonicalTBoxHasher.PackMetadata(
                "hash-golden-stability", "Hash Golden Stability", 1, "closed", "conformance", "v1"));
        assertEquals(((Map<String, Object>) vector.get("expected")).get("tbox_hash"), hash);
    }

    @Test
    void internalStabilityAddsNoHashKey() {
        Map<String, Object> base = new LinkedHashMap<>(Map.of("id", "p", "domain", "A", "range", "A"));
        Map<String, Object> internal = new LinkedHashMap<>(base);
        internal.put("stability", "internal");
        Map<String, Object> stable = new LinkedHashMap<>(base);
        stable.put("stability", "stable");
        String plain = hashOf(base);
        assertEquals(plain, hashOf(internal));
        assertFalse(plain.equals(hashOf(stable)));
        assertMessage("Property 'p' has unknown stability 'frozen': use stable or internal",
                () -> hashOf(Map.of("id", "p", "stability", "frozen")));
    }

    private static String hashOf(Map<String, Object> property) {
        return CanonicalTBoxHasher.hashPackMapping(Map.of("id", "h", "openness", "closed",
                "classes", List.of(Map.of("id", "A")), "properties", List.of(property)));
    }

    // ---- validation -----------------------------------------------------------------------------

    @Test
    void stableRelationMustDeclareDomainAndRange() {
        TBox tbox = new TBox(classes("Customer"), Map.of("hasShipment",
                prop("hasShipment", "Customer", null, false, RelationStability.STABLE)), List.of());
        assertMessage("Stable relation 'hasShipment' must declare a domain and a range",
                () -> OntologyValidator.validate(tbox));
    }

    @Test
    void stableDerivedRelationNeedsADerivation() {
        TBox tbox = new TBox(classes("Customer", "Shipment"), Map.of("hasShipment",
                prop("hasShipment", "Customer", "Shipment", true, RelationStability.STABLE)), List.of());
        assertMessage("Stable derived relation 'hasShipment' has no derivation: declare a property chain"
                + " that implies it, an inverse, or a sub-property of it", () -> OntologyValidator.validate(tbox));
    }

    @Test
    void chainInverseAndSubPropertyEachBackAStableDerivedRelation() {
        Map<String, ClassDef> classes = classes("Customer", "PurchaseOrder", "Shipment");
        PropertyDef hasShipment = prop("hasShipment", "Customer", "Shipment", true, RelationStability.STABLE);
        PropertyDef hasPO = prop("hasPO", "Customer", "PurchaseOrder", false, RelationStability.INTERNAL);
        PropertyDef poShipment = prop("poShipment", "PurchaseOrder", "Shipment", false, RelationStability.INTERNAL);
        // chain
        OntologyValidator.validate(new TBox(classes, props(hasShipment, hasPO, poShipment),
                List.of(new PropertyChainDef(List.of("hasPO", "poShipment"), "hasShipment"))));
        // another property declaring inverseOf it
        PropertyDef shipmentOf = new PropertyDef("shipmentOf", Optional.of("Shipment"), Optional.of("Customer"), true,
                Optional.of("hasShipment"), false, false, false, Set.of());
        OntologyValidator.validate(new TBox(classes, props(hasShipment, shipmentOf), List.of()));
        // it declaring inverseOf another property
        PropertyDef inverseSide = new PropertyDef("hasShipment", Optional.of("Customer"), Optional.of("Shipment"), true,
                Optional.of("shipmentOf"), false, false, false, Set.of(), true, null, Set.of(), Map.of(),
                RelationStability.STABLE);
        PropertyDef plainShipmentOf = prop("shipmentOf", "Shipment", "Customer", false, RelationStability.INTERNAL);
        OntologyValidator.validate(new TBox(classes, props(inverseSide, plainShipmentOf), List.of()));
        // a sub-property of it
        PropertyDef directShipment = new PropertyDef("directShipment", Optional.of("Customer"), Optional.of("Shipment"),
                false, Optional.empty(), false, false, false, Set.of("hasShipment"));
        OntologyValidator.validate(new TBox(classes, props(hasShipment, directShipment), List.of()));
    }

    @Test
    void internalDerivedAndStableAssertedRelationsNeedNoDerivation() {
        Map<String, ClassDef> classes = classes("Customer", "Shipment");
        OntologyValidator.validate(new TBox(classes, props(
                prop("a", "Customer", "Shipment", true, RelationStability.INTERNAL),
                prop("b", "Customer", "Shipment", false, RelationStability.STABLE)), List.of()));
    }

    @Test
    void stableRulesReportTheFirstPropertyInNameOrder() {
        TBox tbox = new TBox(classes("Customer", "Shipment"), props(
                prop("zeta", "Customer", null, false, RelationStability.STABLE),
                prop("alpha", "Customer", "Shipment", true, RelationStability.STABLE)), List.of());
        assertMessage("Stable derived relation 'alpha' has no derivation: declare a property chain"
                + " that implies it, an inverse, or a sub-property of it", () -> OntologyValidator.validate(tbox));
    }

    // ---- loaders --------------------------------------------------------------------------------

    private static final String HAS_SHIPMENT_YAML = """
            classes:
              - id: Party
              - id: Customer
                parents: [Party]
              - id: PurchaseOrder
              - id: POLine
              - id: Shipment
            properties:
              - id: hasPO
                domain: Customer
                range: PurchaseOrder
              - id: hasLine
                domain: PurchaseOrder
                range: POLine
              - id: lineShipment
                domain: POLine
                range: Shipment
              - id: hasShipment
                domain: Customer
                range: Shipment
                stability: stable
                derivation: derived
            chains:
              - chain: [hasPO, hasLine, lineShipment]
                implies: hasShipment
            """;

    @Test
    void yamlLoaderReadsStabilityAndDerivation() throws Exception {
        TBox tbox = loadYaml(HAS_SHIPMENT_YAML);
        PropertyDef hasShipment = tbox.properties().get("hasShipment");
        assertTrue(hasShipment.isStable());
        assertTrue(hasShipment.inferred());
        assertEquals(RelationStability.INTERNAL, tbox.properties().get("hasPO").stability());
    }

    @Test
    void yamlLoaderRejectsInvalidContractFieldsInNameOrder() {
        assertMessage("Property 'hasShipment' has unknown stability 'frozen': use stable or internal",
                () -> loadYaml(HAS_SHIPMENT_YAML.replace("stability: stable", "stability: Frozen")));
        assertMessage("Property 'hasShipment' has unknown derivation 'maybe': use asserted or derived",
                () -> loadYaml(HAS_SHIPMENT_YAML.replace("derivation: derived", "derivation: maybe")));
        assertMessage("Property 'hasShipment' declares derivation 'derived' but inferred is false",
                () -> loadYaml(HAS_SHIPMENT_YAML.replace("derivation: derived", "derivation: derived\n    inferred: false")));
        // Properties are checked in name order: hasLine sorts before hasShipment, so its error wins
        // even though hasShipment's unknown stability is a check that runs earlier per property
        String twoErrors = HAS_SHIPMENT_YAML.replace("stability: stable", "stability: bogus")
                .replace("range: POLine", "range: POLine\n    derivation: sometimes");
        assertMessage("Property 'hasLine' has unknown derivation 'sometimes': use asserted or derived",
                () -> loadYaml(twoErrors));
    }

    @Test
    void yamlLoaderRunsTheStableValidationRules() {
        assertMessage("Stable derived relation 'hasShipment' has no derivation: declare a property chain"
                        + " that implies it, an inverse, or a sub-property of it",
                () -> loadYaml(HAS_SHIPMENT_YAML.substring(0, HAS_SHIPMENT_YAML.indexOf("chains:"))));
    }

    private static TBox loadYaml(String yaml) throws Exception {
        return new YamlOntologyLoader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void annotationLoaderCarriesStabilityAndDerivation() {
        TBox tbox = new AnnotationOntologyLoader().loadFromPackages(List.of(StableFixtures.class.getPackageName() + ".stablefixtures"));
        // ownedBy is declared on two classes, only one of them stable: stable wins whatever the scan order
        PropertyDef owner = tbox.properties().get("ownedBy");
        assertTrue(owner.isStable());
        assertFalse(owner.inferred());
        PropertyDef derived = tbox.properties().get("derivedLink");
        assertEquals(RelationStability.INTERNAL, derived.stability());
        assertTrue(derived.inferred());
    }

    @Test
    void annotationLoaderRejectsAnUnknownStability() {
        assertMessage("Property 'broken' has unknown stability 'sometimes': use stable or internal",
                () -> new AnnotationOntologyLoader().loadFromPackages(
                        List.of(StableFixtures.class.getPackageName() + ".brokenfixtures")));
    }

    // ---- explicit edges -------------------------------------------------------------------------

    @Test
    void explicitEdgesForStableDerivedRelationsAreRejected() throws Exception {
        OntologyRegistry registry = OntologyRegistry.inMemory(loadYaml(HAS_SHIPMENT_YAML));
        Reasoner.Edge explicit = new Reasoner.Edge("c1", "Customer", "hasShipment", "s1", "Shipment", false, Optional.empty());
        StableRelationGuard.StableDerivedEdgeException ex = assertThrows(StableRelationGuard.StableDerivedEdgeException.class,
                () -> new MaterializationEngine(registry).materialize("t", "c1", "Customer", List.of(explicit)));
        assertEquals("Explicit edge for stable derived relation 'hasShipment' is not allowed: its edges come only"
                + " from its derivation", ex.getMessage());
        assertEquals(List.of("hasShipment"), ex.rejectedPredicates());
        // Every edge in the explicit input is explicit, whatever its inferred flag
        Reasoner.Edge flaggedInferred = new Reasoner.Edge("c1", "Customer", "hasShipment", "s1", "Shipment", true, Optional.empty());
        assertThrows(StableRelationGuard.StableDerivedEdgeException.class,
                () -> StableRelationGuard.requireExplicitEdgesAllowed(registry, List.of(flaggedInferred)));
        // Only computed-provider edges are exempt
        Reasoner.Edge computed = new Reasoner.Edge("c1", "Customer", "hasShipment", "s1", "Shipment", false,
                Optional.of(new Reasoner.Provenance("computed", Map.of("providerId", "p"))));
        StableRelationGuard.requireExplicitEdgesAllowed(registry, List.of(computed));
    }

    @Test
    void explicitEdgesForInternalDerivedRelationsKeepWorking() throws Exception {
        OntologyRegistry registry = OntologyRegistry.inMemory(loadYaml(HAS_SHIPMENT_YAML.replace("    stability: stable\n", "")));
        Reasoner.Edge explicit = new Reasoner.Edge("c1", "Customer", "hasShipment", "s1", "Shipment", false, Optional.empty());
        assertTrue(new MaterializationEngine(registry).materialize("t", "c1", "Customer", List.of(explicit)).contains(explicit));
    }

    // ---- binding guard --------------------------------------------------------------------------

    @Test
    void bindingGuardReportsUnknownAndInternalRelationsOnceInFirstOccurrenceOrder() throws Exception {
        TBox tbox = loadYaml(HAS_SHIPMENT_YAML);
        List<StableRelationGuard.BindingViolation> violations = StableRelationGuard.bindingViolations(tbox,
                List.of("hasPO", "hasShipment", "nope", "hasPO"), "orders-view");
        assertEquals(List.of("hasPO", "nope"), violations.stream().map(StableRelationGuard.BindingViolation::relation).toList());
        assertEquals(StableRelationGuard.BindingCode.BINDING_INTERNAL_RELATION, violations.get(0).code());
        assertEquals("binding_internal_relation", violations.get(0).code().wireValue());
        assertEquals("Consumer 'orders-view' binds internal relation 'hasPO': only stable relations may be bound",
                violations.get(0).message());
        assertEquals("binding_unknown_relation", violations.get(1).code().wireValue());
        assertEquals("Consumer 'orders-view' binds unknown relation 'nope'", violations.get(1).message());
        assertTrue(StableRelationGuard.bindingViolations(OntologyRegistry.inMemory(tbox), List.of("hasShipment"), "x").isEmpty());
    }

    // ---- merge ----------------------------------------------------------------------------------

    @Test
    void mergeKeepsStableIfEitherSideDeclaresIt() {
        Map<String, ClassDef> classes = classes("Customer", "Shipment");
        PropertyDef stable = prop("hasShipment", "Customer", "Shipment", false, RelationStability.STABLE);
        PropertyDef internal = prop("hasShipment", "Customer", "Shipment", false, RelationStability.INTERNAL);
        assertTrue(OntologyMerger.merge(new TBox(classes, props(stable), List.of()),
                new TBox(Map.of(), props(internal), List.of())).properties().get("hasShipment").isStable());
        assertTrue(OntologyMerger.merge(new TBox(classes, props(internal), List.of()),
                new TBox(Map.of(), props(stable), List.of())).properties().get("hasShipment").isStable());
        assertFalse(OntologyMerger.merge(new TBox(classes, props(internal), List.of()),
                new TBox(Map.of(), props(internal), List.of())).properties().get("hasShipment").isStable());
    }

    @Test
    void referenceCloserPreservesStability() {
        TBox tbox = new TBox(classes("Customer"), props(prop("hasShipment", "Customer", "Shipment", false,
                RelationStability.STABLE)), List.of());
        TBox closed = OntologyReferenceCloser.closeClassReferences(tbox);
        assertTrue(closed.properties().get("hasShipment").isStable());
        assertTrue(OntologyReferenceCloser.isExternalStub(closed.classes().get("Shipment")));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable action) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, action);
        assertEquals(expected, ex.getMessage());
    }

    private static Map<String, ClassDef> classes(String... names) {
        Map<String, ClassDef> out = new LinkedHashMap<>();
        for (String n : names) out.put(n, new ClassDef(n, Set.of(), Set.of(), Set.of()));
        return out;
    }

    private static Map<String, PropertyDef> props(PropertyDef... defs) {
        Map<String, PropertyDef> out = new LinkedHashMap<>();
        for (PropertyDef d : defs) out.put(d.name(), d);
        return out;
    }

    private static PropertyDef prop(String name, String domain, String range, boolean inferred, RelationStability stability) {
        return new PropertyDef(name, Optional.ofNullable(domain), Optional.ofNullable(range), false, Optional.empty(),
                false, false, false, Set.of(), inferred, null, Set.of(), Map.of(), stability);
    }

    /** Anchor for the fixture packages scanned by the annotation loader tests. */
    static final class StableFixtures {
        private StableFixtures() {}
    }
}
