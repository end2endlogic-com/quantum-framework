package com.e2eq.framework.model.securityrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The trim record is the part a provenance receipt projects, so its guarantees are pinned here:
 * attribution survives composition, the record is immutable once handed out, and "unconstrained"
 * stays distinguishable from "constrained but with no clause".
 */
class GovernedFilterProjectionTest {

    private static GovernedFilterProjection.RuleTrim trim(String name, String resolved) {
        return new GovernedFilterProjection.RuleTrim(name, "dataDomain.tenantId:${pTenantId}", null,
                "AND", resolved);
    }

    @Test
    @DisplayName("carries the filter alongside the rules that produced it")
    void carriesFilterAndAttribution() {
        GovernedFilterProjection projection = GovernedFilterProjection.of(
                "(dataDomain.tenantId:acme)",
                RuleEffect.ALLOW,
                List.of(trim("segment-emea-only", "dataDomain.tenantId:acme")),
                Set.of("bankAccount"));

        assertEquals("(dataDomain.tenantId:acme)", projection.filter().orElseThrow());
        assertEquals(RuleEffect.ALLOW, projection.finalEffect());
        assertEquals(1, projection.ruleTrims().size());
        assertEquals("segment-emea-only", projection.ruleTrims().get(0).ruleName());
        assertTrue(projection.trimmed());
    }

    @Test
    @DisplayName("keeps the authored clause beside the resolved one")
    void keepsAuthoredAndResolvedClauses() {
        GovernedFilterProjection.RuleTrim ruleTrim = trim("segment-emea-only", "dataDomain.tenantId:acme");

        // The authored clause is the rule a steward wrote; the resolved clause is what it meant
        // for this principal. A receipt that showed only one of them would answer the wrong
        // question -- either "what is the policy" or "what happened", never both.
        assertEquals("dataDomain.tenantId:${pTenantId}", ruleTrim.andClause());
        assertEquals("dataDomain.tenantId:acme", ruleTrim.resolvedClause());
    }

    @Test
    @DisplayName("an empty filter with exclusions still counts as trimmed")
    void fieldExclusionsAloneCountAsTrimmed() {
        GovernedFilterProjection projection = GovernedFilterProjection.of(
                null, RuleEffect.ALLOW, List.of(), Set.of("bankAccount", "pricing.unitPrice"));

        assertTrue(projection.filter().isEmpty());
        assertTrue(projection.trimmed(),
                "field-level redaction narrows the read even when no clause does");
        assertEquals(2, projection.excludedFieldPaths().size());
    }

    @Test
    @DisplayName("an entirely unconstrained read is not trimmed")
    void unconstrainedReadIsNotTrimmed() {
        GovernedFilterProjection projection = GovernedFilterProjection.of(
                null, RuleEffect.ALLOW, List.of(), Set.of());

        assertTrue(projection.filter().isEmpty());
        assertFalse(projection.trimmed());
    }

    @Test
    @DisplayName("the record cannot be mutated after it is handed to a receipt writer")
    void recordIsImmutable() {
        GovernedFilterProjection projection = GovernedFilterProjection.of(
                "(a:b)", RuleEffect.ALLOW, List.of(trim("r", "a:b")), Set.of("bankAccount"));

        assertThrows(UnsupportedOperationException.class,
                () -> projection.ruleTrims().add(trim("injected", "x:y")));
        assertThrows(UnsupportedOperationException.class,
                () -> projection.excludedFieldPaths().add("injected"));
    }

    @Test
    @DisplayName("exclusions preserve their order across rules")
    void exclusionOrderIsStable() {
        GovernedFilterProjection projection = GovernedFilterProjection.of(
                null,
                RuleEffect.ALLOW,
                List.of(),
                new java.util.LinkedHashSet<>(List.of("taxId", "bankAccount", "pricing.unitPrice")));

        assertEquals(List.of("taxId", "bankAccount", "pricing.unitPrice"),
                List.copyOf(projection.excludedFieldPaths()));
    }
}
