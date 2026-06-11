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
