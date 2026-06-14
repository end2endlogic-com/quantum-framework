package com.e2eq.ontology.policy;

import com.e2eq.framework.model.securityrules.RuleVocabularyCheck;
import com.e2eq.ontology.core.InMemoryOntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyVocabularyGuardTest {

    private static OntologyRegistry registryWith(String... predicates) {
        Map<String, OntologyRegistry.PropertyDef> properties = new java.util.HashMap<>();
        for (String predicate : predicates) {
            properties.put(predicate, new OntologyRegistry.PropertyDef(predicate,
                    Optional.empty(), Optional.empty(), false, Optional.empty(),
                    false, false, false, Set.of(), false));
        }
        return new InMemoryOntologyRegistry(new OntologyRegistry.TBox(Map.of(), properties, List.of()));
    }

    @Test
    void passesRulesWhoseVocabularyIsDeclared() {
        RuleVocabularyCheck check = new RuleVocabularyCheck("allow-visible",
                List.of("hasEdge(\"canSeeLocation\", rcontext.resourceId)"));

        assertDoesNotThrow(() -> PolicyVocabularyGuard.check(check, registryWith("canSeeLocation")));
    }

    @Test
    void rejectsRulesReferencingUnknownPredicates() {
        RuleVocabularyCheck check = new RuleVocabularyCheck("stale-rule",
                List.of("hasEdge(\"canViewLocation\", rcontext.resourceId)"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PolicyVocabularyGuard.check(check, registryWith("canSeeLocation")));

        assertTrue(failure.getMessage().contains("canViewLocation"));
        assertTrue(failure.getMessage().contains("stale-rule"));
    }

    @Test
    void skipsWhenNoOntologyVocabularyIsConfigured() {
        RuleVocabularyCheck check = new RuleVocabularyCheck("any-rule",
                List.of("hasEdge(\"whatever\", x)"));

        assertDoesNotThrow(() -> PolicyVocabularyGuard.check(check, registryWith()));
        assertDoesNotThrow(() -> PolicyVocabularyGuard.check(check, null));
    }

    @Test
    void ignoresRulesWithoutOntologyReferences() {
        RuleVocabularyCheck check = new RuleVocabularyCheck("plain-rule",
                List.of("pcontext.userId == rcontext.ownerId", "dataDomain.tenantId:${pTenantId}"));

        assertDoesNotThrow(() -> PolicyVocabularyGuard.check(check, registryWith("canSeeLocation")));
    }

    @Test
    void functionalDomainValidationSkipsWhenRegistryEmptyAndPassesWildcards() {
        RuleVocabularyCheck anyDomain = new RuleVocabularyCheck("r", List.of(), "sales", "anything", "view");
        assertDoesNotThrow(() -> PolicyVocabularyGuard.checkFunctionalDomain(anyDomain, Map.of()));

        Map<String, Set<String>> registry = Map.of("order_line", Set.of("view", "create"));
        RuleVocabularyCheck wildcard = new RuleVocabularyCheck("r", List.of(), "*", "*", "*");
        assertDoesNotThrow(() -> PolicyVocabularyGuard.checkFunctionalDomain(wildcard, registry));
    }

    @Test
    void functionalDomainValidationAcceptsDeclaredDomainAndAction() {
        Map<String, Set<String>> registry = Map.of("order_line", Set.of("view", "create"));
        RuleVocabularyCheck check = new RuleVocabularyCheck("r", List.of(), "orders", "Order_Line", "VIEW");

        assertDoesNotThrow(() -> PolicyVocabularyGuard.checkFunctionalDomain(check, registry));
    }

    @Test
    void functionalDomainValidationRejectsUnknownDomainAndAction() {
        Map<String, Set<String>> registry = Map.of("order_line", Set.of("view", "create"));

        RuleVocabularyCheck badDomain = new RuleVocabularyCheck("stale", List.of(), "orders", "order_lines", "view");
        IllegalStateException domainFailure = assertThrows(IllegalStateException.class,
                () -> PolicyVocabularyGuard.checkFunctionalDomain(badDomain, registry));
        assertTrue(domainFailure.getMessage().contains("order_lines"));

        RuleVocabularyCheck badAction = new RuleVocabularyCheck("stale", List.of(), "orders", "order_line", "approve");
        IllegalStateException actionFailure = assertThrows(IllegalStateException.class,
                () -> PolicyVocabularyGuard.checkFunctionalDomain(badAction, registry));
        assertTrue(actionFailure.getMessage().contains("approve"));
    }
}
