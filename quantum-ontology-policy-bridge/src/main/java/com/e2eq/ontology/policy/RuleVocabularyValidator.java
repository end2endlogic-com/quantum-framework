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
 * pinned OntologyRegistry (Q3 in the unified ontology design).
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

    /**
     * Why a rule's predicate reference is rejected.
     * <ul>
     *   <li>{@link #ABSENT} — the predicate is not declared in the TBox at all
     *       (undeclared / renamed-away). Today's behavior.</li>
     *   <li>{@link #PROVISIONAL} — the predicate IS declared in the TBox but has
     *       not been admitted for policy use in this realm (B5 vocabulary tier).</li>
     * </ul>
     */
    public enum Reason { ABSENT, PROVISIONAL }

    public record Violation(String ruleName, String helperFunction, String predicate, Reason reason) {
        @Override
        public String toString() {
            if (reason == Reason.PROVISIONAL) {
                return "rule '" + ruleName + "' references provisional ontology predicate '" + predicate
                        + "' via " + helperFunction + "(...); it is declared but not admitted for policy use"
                        + " — POST /v1/ontology/{realm}/vocabulary/" + predicate + ":promote to admit it"
                        + " before referencing it in a security rule";
            }
            return "rule '" + ruleName + "' references unknown ontology predicate '" + predicate
                    + "' via " + helperFunction + "(...)";
        }
    }

    public record RuleSource(String ruleName, Collection<String> scriptsAndFilters) {}

    private final OntologyRegistry registry;

    /**
     * Predicates admitted for policy use in this realm. {@code null} means
     * "all declared predicates are admitted" (legacy / back-compat): every
     * predicate the registry declares is accepted for rule use.
     */
    private final Set<String> admittedPredicates;

    /** Back-compat: all declared predicates are admitted (no provisional tier). */
    public RuleVocabularyValidator(OntologyRegistry registry) {
        this(registry, null);
    }

    /**
     * @param admittedPredicates the realm's admitted-predicate set, or {@code null}
     *        to admit every declared predicate (legacy behavior).
     */
    public RuleVocabularyValidator(OntologyRegistry registry, Set<String> admittedPredicates) {
        this.registry = registry;
        this.admittedPredicates = admittedPredicates;
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
                        // Not declared in the TBox at all.
                        violations.add(new Violation(rule.ruleName(), matcher.group(1), predicate, Reason.ABSENT));
                    } else if (admittedPredicates != null && !admittedPredicates.contains(predicate)) {
                        // Declared but not admitted for policy use in this realm.
                        violations.add(new Violation(rule.ruleName(), matcher.group(1), predicate, Reason.PROVISIONAL));
                    }
                    // else: declared and (admittedPredicates == null || admitted) -> accept.
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
