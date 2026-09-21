package com.e2eq.ontology.policy;

import com.e2eq.ontology.core.OntologyRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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
 * references and edge qualification property references from precondition/postcondition
 * scripts and filter strings and fails fast on terms the registry does not declare,
 * naming the rule and the offending literal.
 *
 * <p>Enforcement wiring: call {@link #validateOrThrow} wherever policies are
 * loaded (YamlPolicyLoader consumers, PolicyRepo import paths) once the
 * caller has a tenant-resolved registry. Kept dependency-free of the
 * framework's YamlRule type so it can sit in the policy bridge.
 */
public final class RuleVocabularyValidator {

    /** Ontology-aware script/filter helpers whose first argument is a predicate. */
    private static final Pattern PREDICATE_CALL = Pattern.compile(
            "\\b(hasEdge|notHasEdge|hasIncomingEdge|hasOutgoingEdge|hasAnyEdge|hasAnyEdges|hasAllEdges|relatedIds)\\s*\\(\\s*[\"']([A-Za-z_][\\w.-]*)[\"']");

    /** Helper calls that may carry an optional 3rd argument for edge qualification filtering. */
    private static final Pattern EDGE_CALL = Pattern.compile(
            "\\b(hasEdge|notHasEdge|hasIncomingEdge|hasOutgoingEdge)\\s*\\(");

    /** Property key preceding a colon in query or JSON edge filter syntax. */
    private static final Pattern PROPERTY_KEY = Pattern.compile(
            "(?:[\"']([A-Za-z_$\\w.-]*)[\"']|(?<![\\w$])([A-Za-z_$\\w.-]+))\\s*:");

    /** Property access on edge parameter in callback functions (e.g. edge.role, e.props.role). */
    private static final Pattern CALLBACK_PROP_ACCESS = Pattern.compile(
            "\\b(?:e|edge|props)\\.([A-Za-z_][\\w.-]*)");

    /** Root edge fields inherent to the edge data model that do not require TBox property declarations. */
    private static final Set<String> ROOT_EDGE_FIELDS = Set.of(
            "src", "srcType", "p", "predicate", "dst", "dstType",
            "inferred", "derived", "props", "prov", "support", "ts",
            "dataDomain", "_id", "id", "displayName", "createDate", "lastModifiedDate",
            "version", "_version"
    );

    /**
     * Why a rule's predicate or edge property reference is rejected.
     * <ul>
     *   <li>{@link #ABSENT} — the term is not declared in the TBox at all
     *       (undeclared / renamed-away). Today's behavior.</li>
     *   <li>{@link #PROVISIONAL} — the term IS declared in the TBox but has
     *       not been admitted for policy use in this realm (B5 vocabulary tier).</li>
     * </ul>
     */
    public enum Reason { ABSENT, PROVISIONAL }

    public record Violation(String ruleName, String helperFunction, String predicate, Reason reason, String termType) {
        public Violation(String ruleName, String helperFunction, String predicate, Reason reason) {
            this(ruleName, helperFunction, predicate, reason, "predicate");
        }

        @Override
        public String toString() {
            String typeName = termType != null ? termType : "predicate";
            if (reason == Reason.PROVISIONAL) {
                return "rule '" + ruleName + "' references provisional ontology " + typeName + " '" + predicate
                        + "' via " + helperFunction + "(...); it is declared but not admitted for policy use"
                        + " — POST /v1/ontology/{realm}/vocabulary/" + predicate + ":promote to admit it"
                        + " before referencing it in a security rule";
            }
            return "rule '" + ruleName + "' references unknown ontology " + typeName + " '" + predicate
                    + "' via " + helperFunction + "(...)";
        }
    }

    public record RuleSource(String ruleName, Collection<String> scriptsAndFilters) {}

    private final OntologyRegistry registry;

    /**
     * Predicates admitted for policy use in this realm. {@code null} means
     * "all declared predicates are admitted" (legacy / back-compat): every
     * predicate the registry declares is accepted for rule use — UNLESS
     * {@link #failClosed} is set, in which case NO predicate is admitted.
     */
    private final Set<String> admittedPredicates;

    /**
     * B5 fail-CLOSED mode. When {@code true}, the realm's admitted set could not be
     * read (Mongo UNAVAILABLE) and we must NOT fall back to all-admitted: every
     * declared predicate referenced by a rule is treated as {@link Reason#PROVISIONAL}
     * (not admitted). This converts a transient read failure into a rule-registration
     * rejection rather than a silent security downgrade to accept-every-predicate.
     */
    private final boolean failClosed;

    /** Back-compat: all declared predicates are admitted (no provisional tier). */
    public RuleVocabularyValidator(OntologyRegistry registry) {
        this(registry, null, false);
    }

    /**
     * @param admittedPredicates the realm's admitted-predicate set, or {@code null}
     *        to admit every declared predicate (legacy behavior).
     */
    public RuleVocabularyValidator(OntologyRegistry registry, Set<String> admittedPredicates) {
        this(registry, admittedPredicates, false);
    }

    /**
     * @param admittedPredicates the realm's admitted-predicate set, or {@code null}
     *        to admit every declared predicate (legacy behavior). Ignored when
     *        {@code failClosed} is {@code true}.
     * @param failClosed when {@code true} (B5 UNAVAILABLE), admit NO predicate for
     *        rule use: every declared predicate referenced by a rule is rejected as
     *        provisional. Use this when the admitted set could not be read so the
     *        guard fails closed instead of downgrading to all-admitted.
     */
    public RuleVocabularyValidator(OntologyRegistry registry, Set<String> admittedPredicates, boolean failClosed) {
        this.registry = registry;
        this.admittedPredicates = admittedPredicates;
        this.failClosed = failClosed;
    }

    /**
     * B5 fail-CLOSED factory: the admitted set is UNAVAILABLE (Mongo read failed).
     * No declared predicate is admitted for rule references; absent predicates are
     * still {@link Reason#ABSENT}. Use this rather than the legacy single-arg ctor
     * on the read-failure path so a transient blip does not re-open the vocabulary.
     */
    public static RuleVocabularyValidator failClosed(OntologyRegistry registry) {
        return new RuleVocabularyValidator(registry, null, true);
    }

    public List<Violation> validate(Collection<RuleSource> rules) {
        List<Violation> violations = new ArrayList<>();
        for (RuleSource rule : rules) {
            for (String source : rule.scriptsAndFilters()) {
                if (source == null || source.isBlank()) continue;

                // 1. Validate relationship predicates
                Matcher matcher = PREDICATE_CALL.matcher(source);
                while (matcher.find()) {
                    String predicate = matcher.group(2);
                    if (registry.propertyOf(predicate).isEmpty()) {
                        // Not declared in the TBox at all.
                        violations.add(new Violation(rule.ruleName(), matcher.group(1), predicate, Reason.ABSENT));
                    } else if (failClosed) {
                        // B5 UNAVAILABLE: admitted set could not be read -> admit nothing.
                        violations.add(new Violation(rule.ruleName(), matcher.group(1), predicate, Reason.PROVISIONAL));
                    } else if (admittedPredicates != null && !admittedPredicates.contains(predicate)) {
                        // Declared but not admitted for policy use in this realm.
                        violations.add(new Violation(rule.ruleName(), matcher.group(1), predicate, Reason.PROVISIONAL));
                    }
                }

                // 2. Validate edge qualification property references (3rd argument to edge helpers)
                Matcher edgeMatcher = EDGE_CALL.matcher(source);
                while (edgeMatcher.find()) {
                    String helper = edgeMatcher.group(1);
                    int openParen = edgeMatcher.end() - 1;
                    List<String> args = extractArguments(source, openParen);
                    if (args.size() >= 3) {
                        String filterArg = args.get(2).trim();
                        Set<String> referencedProps = new LinkedHashSet<>();
                        if (filterArg.startsWith("{") && filterArg.endsWith("}")) {
                            String inner = filterArg.substring(1, filterArg.length() - 1);
                            Matcher keyMatcher = PROPERTY_KEY.matcher(inner);
                            while (keyMatcher.find()) {
                                String prop = keyMatcher.group(1) != null ? keyMatcher.group(1) : keyMatcher.group(2);
                                if (prop != null) {
                                    if (prop.startsWith("props.")) {
                                        prop = prop.substring(6);
                                    }
                                    if (!prop.startsWith("$") && !ROOT_EDGE_FIELDS.contains(prop)) {
                                        referencedProps.add(prop);
                                    }
                                }
                            }
                        } else {
                            // Check callback functions: e.g. e => e.role === "PRIMARY" or edge.role
                            Matcher cbMatcher = CALLBACK_PROP_ACCESS.matcher(filterArg);
                            while (cbMatcher.find()) {
                                String prop = cbMatcher.group(1);
                                if (prop != null) {
                                    if (prop.startsWith("props.")) {
                                        prop = prop.substring(6);
                                    }
                                    if (!prop.startsWith("$") && !ROOT_EDGE_FIELDS.contains(prop)) {
                                        referencedProps.add(prop);
                                    }
                                }
                            }
                        }

                        for (String prop : referencedProps) {
                            if (registry.propertyOf(prop).isEmpty()) {
                                violations.add(new Violation(rule.ruleName(), helper, prop, Reason.ABSENT, "edge property"));
                            } else if (failClosed) {
                                violations.add(new Violation(rule.ruleName(), helper, prop, Reason.PROVISIONAL, "edge property"));
                            } else if (admittedPredicates != null && !admittedPredicates.contains(prop)) {
                                violations.add(new Violation(rule.ruleName(), helper, prop, Reason.PROVISIONAL, "edge property"));
                            }
                        }
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

    static List<String> extractArguments(String s, int openParenIdx) {
        List<String> args = new ArrayList<>();
        int depth = 1;
        int start = openParenIdx + 1;
        boolean inQuote = false;
        char quoteChar = 0;
        int braceDepth = 0;
        int bracketDepth = 0;

        for (int i = openParenIdx + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quoteChar && s.charAt(i - 1) != '\\') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
                continue;
            }
            if (c == '{') braceDepth++;
            else if (c == '}') braceDepth--;
            else if (c == '[') bracketDepth++;
            else if (c == ']') bracketDepth--;
            else if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    args.add(s.substring(start, i).trim());
                    break;
                }
            } else if (c == ',' && depth == 1 && braceDepth == 0 && bracketDepth == 0) {
                args.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        return args;
    }
}
