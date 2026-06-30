package com.e2eq.ontology.policy;

import com.e2eq.ontology.core.InMemoryOntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleVocabularyValidatorTest {

    private static OntologyRegistry registry() {
        Map<String, OntologyRegistry.ClassDef> classes = Map.of(
                "Associate", new OntologyRegistry.ClassDef("Associate", Set.of(), Set.of(), Set.of()),
                "Location", new OntologyRegistry.ClassDef("Location", Set.of(), Set.of(), Set.of()));
        Map<String, OntologyRegistry.PropertyDef> properties = Map.of(
                "canSeeLocation", new OntologyRegistry.PropertyDef("canSeeLocation",
                        Optional.of("Associate"), Optional.of("Location"),
                        false, Optional.empty(), false, false, false, Set.of(), false));
        return new InMemoryOntologyRegistry(new OntologyRegistry.TBox(classes, properties, List.of()));
    }

    @Test
    void acceptsRulesReferencingDeclaredPredicates() {
        RuleVocabularyValidator validator = new RuleVocabularyValidator(registry());

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("allow-visible-locations", List.of(
                        "hasEdge(\"canSeeLocation\", rcontext.resourceId)",
                        "_id:${accessibleLocationIds}"))));

        assertEquals(List.of(), violations);
    }

    @Test
    void flagsUnknownPredicatesWithRuleAndHelperContext() {
        RuleVocabularyValidator validator = new RuleVocabularyValidator(registry());

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("stale-rule", List.of(
                        "hasEdge('canViewLocation', rcontext.resourceId) || hasAnyEdges(\"ownsTerritory\")"))));

        assertEquals(2, violations.size());
        assertEquals("canViewLocation", violations.get(0).predicate());
        assertEquals("hasEdge", violations.get(0).helperFunction());
        assertEquals("ownsTerritory", violations.get(1).predicate());
        assertTrue(violations.get(0).toString().contains("stale-rule"));
    }

    @Test
    void validateOrThrowFailsFastNamingTheViolations() {
        RuleVocabularyValidator validator = new RuleVocabularyValidator(registry());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                validator.validateOrThrow(List.of(
                        new RuleVocabularyValidator.RuleSource("bad-rule",
                                List.of("notHasEdge(\"renamedPredicate\", x)")))));

        assertTrue(failure.getMessage().contains("renamedPredicate"));
        assertTrue(failure.getMessage().contains("bad-rule"));
    }

    private static OntologyRegistry registryWith(String... predicates) {
        Map<String, OntologyRegistry.PropertyDef> properties = new java.util.HashMap<>();
        for (String p : predicates) {
            properties.put(p, new OntologyRegistry.PropertyDef(p,
                    Optional.empty(), Optional.empty(), false, Optional.empty(),
                    false, false, false, Set.of(), false));
        }
        return new InMemoryOntologyRegistry(new OntologyRegistry.TBox(Map.of(), properties, List.of()));
    }

    @Test
    void admittedPredicateAccepted() {
        // canSeeLocation declared AND admitted -> accepted.
        RuleVocabularyValidator validator = new RuleVocabularyValidator(
                registryWith("canSeeLocation", "ownsTerritory"), Set.of("canSeeLocation"));

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("ok-rule",
                        List.of("hasEdge(\"canSeeLocation\", x)"))));

        assertEquals(List.of(), violations);
    }

    @Test
    void declaredButNotAdmittedIsProvisionalWithPromoteMessage() {
        // ownsTerritory declared but NOT in the admitted set -> PROVISIONAL.
        RuleVocabularyValidator validator = new RuleVocabularyValidator(
                registryWith("canSeeLocation", "ownsTerritory"), Set.of("canSeeLocation"));

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("prov-rule",
                        List.of("hasEdge(\"ownsTerritory\", x)"))));

        assertEquals(1, violations.size());
        RuleVocabularyValidator.Violation v = violations.get(0);
        assertEquals("ownsTerritory", v.predicate());
        assertEquals(RuleVocabularyValidator.Reason.PROVISIONAL, v.reason());
        assertTrue(v.toString().contains("provisional"));
        assertTrue(v.toString().contains(":promote"));
        assertTrue(v.toString().contains("ownsTerritory"));
        assertTrue(v.toString().contains("not admitted for policy use"));
    }

    @Test
    void undeclaredIsAbsentRegardlessOfAdmittedSet() {
        // renamedAway is not declared at all -> ABSENT (not PROVISIONAL).
        RuleVocabularyValidator validator = new RuleVocabularyValidator(
                registryWith("canSeeLocation"), Set.of("canSeeLocation"));

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("absent-rule",
                        List.of("hasEdge(\"renamedAway\", x)"))));

        assertEquals(1, violations.size());
        RuleVocabularyValidator.Violation v = violations.get(0);
        assertEquals("renamedAway", v.predicate());
        assertEquals(RuleVocabularyValidator.Reason.ABSENT, v.reason());
        assertTrue(v.toString().contains("unknown ontology predicate"));
    }

    @Test
    void legacyNullAdmittedSetAcceptsAllDeclared() {
        // Single-arg ctor (admittedPredicates == null) -> every declared predicate accepted.
        RuleVocabularyValidator validator = new RuleVocabularyValidator(
                registryWith("canSeeLocation", "ownsTerritory"));

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("legacy-rule",
                        List.of("hasEdge(\"canSeeLocation\", x) || hasEdge(\"ownsTerritory\", y)"))));

        assertEquals(List.of(), violations);

        // ... but an undeclared predicate is still ABSENT under legacy.
        List<RuleVocabularyValidator.Violation> undeclared = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("legacy-rule",
                        List.of("hasEdge(\"nope\", x)"))));
        assertEquals(1, undeclared.size());
        assertEquals(RuleVocabularyValidator.Reason.ABSENT, undeclared.get(0).reason());
    }

    @Test
    void provisionalPredicateStillResolvesForFactEdgePath_noDeadlock() {
        // The fact/edge path (and reasoner) resolves predicates via registry.propertyOf,
        // which is unaffected by the admitted-set tier: a declared-but-not-admitted
        // predicate is fully resolvable for non-rule use. ONLY rule registration
        // (validate(...)) enforces admission, so non-rule usage never deadlocks waiting
        // on promotion.
        OntologyRegistry registry = registryWith("canSeeLocation", "ownsTerritory");
        Set<String> admitted = Set.of("canSeeLocation"); // ownsTerritory is provisional

        // Edge/fact path: predicate resolves regardless of admission tier.
        assertTrue(registry.propertyOf("ownsTerritory").isPresent(),
                "provisional predicate must still resolve via propertyOf (edge/fact path)");

        // Rule path: same provisional predicate IS flagged when referenced by a rule.
        RuleVocabularyValidator validator = new RuleVocabularyValidator(registry, admitted);
        List<RuleVocabularyValidator.Violation> ruleViolations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("rule-using-prov",
                        List.of("hasEdge(\"ownsTerritory\", x)"))));
        assertEquals(1, ruleViolations.size());
        assertEquals(RuleVocabularyValidator.Reason.PROVISIONAL, ruleViolations.get(0).reason());

        // A RuleSource with no ontology helper calls (e.g. a plain fact/edge filter) is
        // never flagged — admission is only consulted for actual rule predicate refs.
        assertEquals(List.of(), validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("non-rule-content",
                        List.of("dataDomain.tenantId:${pTenantId}")))));
    }

    @Test
    void ignoresNonOntologyScriptContent() {
        RuleVocabularyValidator validator = new RuleVocabularyValidator(registry());

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("plain-rule", java.util.Arrays.asList(
                        "pcontext.userId == rcontext.ownerId",
                        "dataDomain.tenantId:${pTenantId}",
                        null,
                        ""))));

        assertEquals(List.of(), violations);
    }
}
