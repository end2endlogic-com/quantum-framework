package com.e2eq.ontology.policy;

import com.e2eq.framework.model.securityrules.RuleVocabularyCheck;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.runtime.AdmittedVocabularyResult;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enforcement wiring for rule vocabulary validation (Q3 in the unified ontology design
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

    /**
     * B5 observability: number of rule-vocabulary checks that hit the UNAVAILABLE
     * (Mongo read failed) path and therefore failed CLOSED. This is the "downgrade
     * attempt was rejected" signal — a non-zero/rising value means a GOVERNED realm
     * is being protected from a fail-OPEN downgrade during a Mongo blip/outage.
     * Mirrors the intended Micrometer counter {@code ontology.vocabulary.read_unavailable};
     * exposed as a plain counter so the policy-bridge needs no metrics dependency and
     * the WARN log remains the primary observable signal.
     */
    static final AtomicLong READ_UNAVAILABLE_COUNT = new AtomicLong();

    /** B5: current value of the read-unavailable (fail-closed) counter. */
    public static long readUnavailableCount() {
        return READ_UNAVAILABLE_COUNT.get();
    }

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
        // B5 vocabulary tier: resolve the realm's 3-state admitted-predicate disposition.
        //   GOVERNED    -> enforce exactly the admitted set (provisional/admitted tier).
        //   LEGACY      -> legacy validator: all declared predicates admitted (back-compat).
        //   UNAVAILABLE -> the admitted-set read FAILED; fail CLOSED (admit nothing for
        //                  rule refs) and record the downgrade-attempt signal. We do NOT
        //                  treat a transient Mongo blip as legacy/all-admitted.
        AdmittedVocabularyResult vocab = AdmittedVocabularyResult.legacy();
        if (registry != null) {
            try {
                vocab = registryProvider.admittedVocabularyForCurrentRealm();
            } catch (RuntimeException e) {
                // The provider already maps read failures to UNAVAILABLE internally; an
                // exception escaping here is itself a read failure -> fail CLOSED too.
                Log.warnf("Rule vocabulary tier resolution threw for '%s'; failing CLOSED (%s)",
                        check.ruleName(), e.getMessage());
                vocab = AdmittedVocabularyResult.unavailable();
            }
        }
        checkVocabulary(check, registry, vocab);
        if (functionalDomainValidationEnabled) {
            checkFunctionalDomain(check, functionalDomainSnapshot());
        }
    }

    /**
     * Legacy entry point: all declared predicates are admitted (no provisional tier).
     * Equivalent to {@code check(check, registry, null)}.
     */
    static void check(RuleVocabularyCheck check, OntologyRegistry registry) {
        check(check, registry, (java.util.Set<String>) null);
    }

    /**
     * Core predicate logic, separated from CDI resolution for direct unit testing.
     * 2-state back-compat overload (LEGACY when {@code admittedPredicates == null},
     * GOVERNED otherwise). Callers that must fail CLOSED on a read failure use the
     * {@link AdmittedVocabularyResult} overload instead.
     *
     * @param admittedPredicates the realm's admitted-predicate set, or {@code null}
     *        to admit every declared predicate (legacy behavior).
     */
    static void check(RuleVocabularyCheck check, OntologyRegistry registry,
                      java.util.Set<String> admittedPredicates) {
        checkVocabulary(check, registry, admittedPredicates == null
                ? AdmittedVocabularyResult.legacy()
                : AdmittedVocabularyResult.governed(admittedPredicates));
    }

    /**
     * Core predicate logic over the B5 3-state vocabulary disposition.
     * <ul>
     *   <li>LEGACY -&gt; legacy validator (all declared admitted).</li>
     *   <li>GOVERNED -&gt; enforce the admitted set (provisional rejected).</li>
     *   <li>UNAVAILABLE -&gt; fail CLOSED: every declared predicate referenced by a
     *       rule is rejected, AND the {@link #READ_UNAVAILABLE_COUNT} counter is
     *       incremented (downgrade-attempt observable). This is an availability
     *       tradeoff: during a Mongo outage, rule (re)loading for a GOVERNED realm is
     *       rejected rather than silently downgraded to accept-every-predicate.</li>
     * </ul>
     */
    static void checkVocabulary(RuleVocabularyCheck check, OntologyRegistry registry,
                                AdmittedVocabularyResult vocab) {
        if (registry == null || registry.properties().isEmpty()) {
            return; // no ontology vocabulary configured — nothing to validate against
        }
        if (vocab == null) {
            vocab = AdmittedVocabularyResult.legacy();
        }
        RuleVocabularyValidator validator;
        switch (vocab.kind()) {
            case UNAVAILABLE -> {
                READ_UNAVAILABLE_COUNT.incrementAndGet();
                Log.warnf("Rule '%s' vocabulary check failing CLOSED: admitted-predicate set is "
                        + "UNAVAILABLE (Mongo read failed). Refusing to downgrade to all-admitted; "
                        + "any declared predicate referenced by this rule is rejected until the read "
                        + "recovers [metric ontology.vocabulary.read_unavailable]", check.ruleName());
                validator = RuleVocabularyValidator.failClosed(registry);
            }
            case GOVERNED -> validator = new RuleVocabularyValidator(registry, vocab.admitted());
            case LEGACY -> validator = new RuleVocabularyValidator(registry, null);
            default -> validator = new RuleVocabularyValidator(registry, null);
        }
        validator.validateOrThrow(List.of(
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
