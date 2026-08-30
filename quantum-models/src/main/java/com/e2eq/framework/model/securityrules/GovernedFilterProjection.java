package com.e2eq.framework.model.securityrules;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The governed filter for a principal, together with the record of what produced it.
 *
 * <p>Composing a governed filter string already resolves every matched ALLOW rule and every
 * field exclusion, and then discards all of it, returning only the composed string. That is
 * enough to execute a query and not enough to explain one. This type keeps both: the filter
 * the store will run, and a per-rule trim record naming which rule contributed which clause
 * and which fields it stripped.
 *
 * <p>The trim record is what a provenance receipt projects. It has to be captured here, at
 * the moment the filter is composed, because it cannot be reconstructed afterwards: the
 * composed string is a conjunction of substituted clauses with no attribution left in it, and
 * re-running the rules later would apply today's policy to yesterday's decision.
 *
 * <p>Instances are immutable and safe to hand to a receipt writer.
 */
public final class GovernedFilterProjection {

    private final String filter;
    private final RuleEffect finalEffect;
    private final List<RuleTrim> ruleTrims;
    private final Set<String> excludedFieldPaths;

    private GovernedFilterProjection(String filter,
                                     RuleEffect finalEffect,
                                     List<RuleTrim> ruleTrims,
                                     Set<String> excludedFieldPaths) {
        this.filter = filter;
        this.finalEffect = Objects.requireNonNull(finalEffect, "finalEffect");
        this.ruleTrims = List.copyOf(ruleTrims);
        this.excludedFieldPaths = Collections.unmodifiableSet(new LinkedHashSet<>(excludedFieldPaths));
    }

    public static GovernedFilterProjection of(String filter,
                                              RuleEffect finalEffect,
                                              List<RuleTrim> ruleTrims,
                                              Set<String> excludedFieldPaths) {
        return new GovernedFilterProjection(filter, finalEffect, ruleTrims, excludedFieldPaths);
    }

    /**
     * The composed governed filter, empty when no rule and no user query constrained the read.
     *
     * <p>Empty means unconstrained, never denied: a denial does not reach this type, because
     * composition throws before a projection is built.
     */
    public Optional<String> filter() {
        return Optional.ofNullable(filter);
    }

    public RuleEffect finalEffect() {
        return finalEffect;
    }

    /** Per-rule attribution for every clause that entered the filter, in composition order. */
    public List<RuleTrim> ruleTrims() {
        return ruleTrims;
    }

    /**
     * Dotted field paths stripped from reads and writes, as the union across matched ALLOW rules.
     *
     * <p>Union rather than intersection: exclusions accumulate, so a field withheld by any
     * matched rule stays withheld regardless of what the others allow.
     */
    public Set<String> excludedFieldPaths() {
        return excludedFieldPaths;
    }

    /** True when policy narrowed the read in any way -- by clause or by field. */
    public boolean trimmed() {
        return !ruleTrims.isEmpty() || !excludedFieldPaths.isEmpty();
    }

    @Override
    public String toString() {
        return "GovernedFilterProjection{effect=" + finalEffect
                + ", rules=" + ruleTrims.size()
                + ", excludedFields=" + excludedFieldPaths.size()
                + ", filtered=" + (filter != null) + "}";
    }

    /** One rule's contribution to the governed filter. */
    public static final class RuleTrim {

        private final String ruleName;
        private final String andClause;
        private final String orClause;
        private final String joinOp;
        private final String resolvedClause;

        public RuleTrim(String ruleName,
                        String andClause,
                        String orClause,
                        String joinOp,
                        String resolvedClause) {
            this.ruleName = ruleName;
            this.andClause = andClause;
            this.orClause = orClause;
            this.joinOp = joinOp;
            this.resolvedClause = resolvedClause;
        }

        /** Name of the rule as authored, so a reviewer sees the rule rather than the clause. */
        public String ruleName() {
            return ruleName;
        }

        /** The rule's and-filter as authored, before variable substitution. */
        public String andClause() {
            return andClause;
        }

        /** The rule's or-filter as authored, before variable substitution. */
        public String orClause() {
            return orClause;
        }

        public String joinOp() {
            return joinOp;
        }

        /**
         * The clause after principal substitution -- what actually constrained the store.
         *
         * <p>Kept alongside the authored form because they answer different questions: the
         * authored clause is the rule a steward wrote, and the resolved clause is what it meant
         * for this principal at this moment.
         */
        public String resolvedClause() {
            return resolvedClause;
        }

        @Override
        public String toString() {
            return "RuleTrim{" + ruleName + " -> " + resolvedClause + "}";
        }
    }
}
