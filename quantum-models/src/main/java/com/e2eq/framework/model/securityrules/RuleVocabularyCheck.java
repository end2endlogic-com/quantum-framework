package com.e2eq.framework.model.securityrules;

import java.util.List;

/**
 * CDI event payload fired when a security rule is registered, carrying the
 * script/filter sources that may reference ontology vocabulary. Observed by
 * the ontology policy bridge (PolicyVocabularyGuard) to fail fast on rules
 * referencing predicates the ontology does not declare — see
 * helixor-ontologies UNIFIED_ONTOLOGY_DESIGN.md (Q3).
 *
 * <p>Lives in quantum-models so both the rule engine (quantum-morphia-repos)
 * and the bridge (quantum-ontology-policy-bridge) can see it without new
 * dependency edges.
 */
public record RuleVocabularyCheck(String ruleName, List<String> scriptsAndFilters) {
}
