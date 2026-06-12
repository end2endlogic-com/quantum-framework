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

    @ConfigProperty(name = "quantum.ontology.rule-vocabulary-validation.enabled", defaultValue = "true")
    boolean enabled;

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
            return;
        }
        check(check, registry);
    }

    /** Core logic, separated from CDI resolution for direct unit testing. */
    static void check(RuleVocabularyCheck check, OntologyRegistry registry) {
        if (registry == null || registry.properties().isEmpty()) {
            return; // no ontology vocabulary configured — nothing to validate against
        }
        new RuleVocabularyValidator(registry).validateOrThrow(List.of(
                new RuleVocabularyValidator.RuleSource(check.ruleName(), check.scriptsAndFilters())));
    }
}
