package com.e2eq.ontology.policy;

import com.e2eq.framework.model.securityrules.RuleVocabularyCheck;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Enforcement wiring for rule vocabulary validation (Q3 in helixor-ontologies
 * UNIFIED_ONTOLOGY_DESIGN.md): observes {@link RuleVocabularyCheck} events
 * fired by RuleContext.addRule and fails the registration when a rule's
 * scripts/filters reference ontology predicates the current registry does not
 * declare.
 *
 * <p>Safety valves:
 * <ul>
 *   <li>{@code quantum.ontology.rule-vocabulary-validation.enabled=false}
 *       disables enforcement (default enabled).</li>
 *   <li>If the registry cannot be resolved (bootstrap, no Mongo yet) the
 *       check is skipped with a debug log — vocabulary validation never
 *       bricks startup ordering.</li>
 *   <li>If the resolved TBox declares no properties the check is skipped:
 *       apps that do not use the ontology are unaffected.</li>
 * </ul>
 */
@ApplicationScoped
public class PolicyVocabularyGuard {

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    @Inject
    com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo functionalDomainRepo;

    @ConfigProperty(name = "quantum.ontology.rule-vocabulary-validation.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "quantum.ontology.functional-domain-validation.enabled", defaultValue = "true")
    boolean functionalDomainValidationEnabled;

    /** Lazy snapshot of the FunctionalDomain registry (domain refName -> action refNames). */
    private volatile java.util.Map<String, java.util.Set<String>> functionalDomainSnapshot;

    void onRuleVocabularyCheck(@Observes RuleVocabularyCheck check) {
        if (!enabled) {
            return;
        }
        OntologyRegistry registry;
        try {
            registry = registryProvider.getRegistry();
        } catch (RuntimeException e) {
            Log.debugf("Skipping rule vocabulary check for '%s': registry unavailable (%s)",
                    check.ruleName(), e.getMessage());
            registry = null;
        }
        check(check, registry);
        if (functionalDomainValidationEnabled) {
            checkFunctionalDomain(check, functionalDomainSnapshot());
        }
    }

    /** Core predicate logic, separated from CDI resolution for direct unit testing. */
    static void check(RuleVocabularyCheck check, OntologyRegistry registry) {
        if (registry == null || registry.properties().isEmpty()) {
            return; // no ontology vocabulary configured — nothing to validate against
        }
        new RuleVocabularyValidator(registry).validateOrThrow(List.of(
                new RuleVocabularyValidator.RuleSource(check.ruleName(), check.scriptsAndFilters())));
    }

    /**
     * Validates the rule header's functionalDomain/action axes against the
     * FunctionalDomain registry. An EMPTY registry skips validation entirely
     * (the registry is opt-in); wildcards and blank axes always pass.
     */
    static void checkFunctionalDomain(RuleVocabularyCheck check,
                                      java.util.Map<String, java.util.Set<String>> domainActions) {
        if (domainActions == null || domainActions.isEmpty()) {
            return;
        }
        String domain = normalizeAxis(check.functionalDomain());
        if (domain == null) {
            return; // wildcard or unset
        }
        java.util.Set<String> actions = domainActions.get(domain);
        if (actions == null) {
            throw new IllegalStateException(
                    "rule '" + check.ruleName() + "' references unknown functional domain '"
                            + check.functionalDomain() + "' (registry declares: "
                            + new java.util.TreeSet<>(domainActions.keySet()) + ")");
        }
        String action = normalizeAxis(check.action());
        if (action != null && !actions.contains(action)) {
            throw new IllegalStateException(
                    "rule '" + check.ruleName() + "' references unknown action '" + check.action()
                            + "' for functional domain '" + check.functionalDomain()
                            + "' (declared actions: " + new java.util.TreeSet<>(actions) + ")");
        }
    }

    private static String normalizeAxis(String value) {
        if (value == null || value.isBlank() || value.equals("*")) {
            return null;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private java.util.Map<String, java.util.Set<String>> functionalDomainSnapshot() {
        java.util.Map<String, java.util.Set<String>> snapshot = functionalDomainSnapshot;
        if (snapshot != null) {
            return snapshot;
        }
        try {
            java.util.Map<String, java.util.Set<String>> loaded = new java.util.HashMap<>();
            for (var domain : functionalDomainRepo.getAllList()) {
                java.util.Set<String> actions = new java.util.HashSet<>();
                if (domain.getFunctionalActions() != null) {
                    domain.getFunctionalActions().forEach(action -> {
                        if (action.getRefName() != null) {
                            actions.add(action.getRefName().toLowerCase(java.util.Locale.ROOT));
                        }
                    });
                }
                if (domain.getRefName() != null) {
                    loaded.put(domain.getRefName().toLowerCase(java.util.Locale.ROOT), actions);
                }
            }
            functionalDomainSnapshot = loaded;
            return loaded;
        } catch (RuntimeException e) {
            Log.debugf("FunctionalDomain registry unavailable; skipping domain/action validation (%s)", e.getMessage());
            return java.util.Map.of();
        }
    }

    /** Invalidate the cached FunctionalDomain snapshot (e.g. after seeding). */
    public void invalidateFunctionalDomainSnapshot() {
        functionalDomainSnapshot = null;
    }
}
