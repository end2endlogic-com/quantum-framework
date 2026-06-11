package com.e2eq.ontology.policy;

import com.e2eq.ontology.core.OntologyRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates the ontology vocabulary referenced by security rules against a
 * pinned OntologyRegistry (Q3 in helixor-ontologies UNIFIED_ONTOLOGY_DESIGN.md).
 *
 * <p>Rule scripts and filter templates reference ontology predicates as string
 * literals (e.g. {@code hasEdge("canSeeLocation", resourceId)}). Today those
 * literals are unvalidated: renaming a property in the ontology silently
 * breaks every rule that references it. This validator extracts predicate
 * references from precondition/postcondition scripts and filter strings and
 * fails fast on terms the registry does not declare, naming the rule and the
 * offending literal.
 *
 * <p>Enforcement wiring: call {@link #validateOrThrow} wherever policies are
 * loaded (YamlPolicyLoader consumers, PolicyRepo import paths) once the
 * caller has a tenant-resolved registry. Kept dependency-free of the
 * framework's YamlRule type so it can sit in the policy bridge.
 */
public final class RuleVocabularyValidator {

    /** Ontology-aware script/filter helpers whose first argument is a predicate. */
    private static final Pattern PREDICATE_CALL = Pattern.compile(
            "\\b(hasEdge|notHasEdge|hasIncomingEdge|hasAnyEdges|hasAllEdges|relatedIds)\\s*\\(\\s*[\"']([A-Za-z_][\\w.-]*)[\"']");

    public record Violation(String ruleName, String helperFunction, String predicate) {
        @Override
        public String toString() {
            return "rule '" + ruleName + "' references unknown ontology predicate '" + predicate
                    + "' via " + helperFunction + "(...)";
        }
    }

    public record RuleSource(String ruleName, Collection<String> scriptsAndFilters) {}

    private final OntologyRegistry registry;

    public RuleVocabularyValidator(OntologyRegistry registry) {
        this.registry = registry;
    }

    public List<Violation> validate(Collection<RuleSource> rules) {
        List<Violation> violations = new ArrayList<>();
        for (RuleSource rule : rules) {
            for (String source : rule.scriptsAndFilters()) {
                if (source == null || source.isBlank()) continue;
                Matcher matcher = PREDICATE_CALL.matcher(source);
                while (matcher.find()) {
                    String predicate = matcher.group(2);
                    if (registry.propertyOf(predicate).isEmpty()) {
                        violations.add(new Violation(rule.ruleName(), matcher.group(1), predicate));
                    }
                }
            }
        }
        return violations;
    }

    public void validateOrThrow(Collection<RuleSource> rules) {
        List<Violation> violations = validate(rules);
        if (!violations.isEmpty()) {
            Set<String> known = registry.properties().keySet();
            throw new IllegalStateException(
                    "Policy vocabulary validation failed against ontology (tbox declares "
                            + known.size() + " properties): " + violations);
        }
    }
}
