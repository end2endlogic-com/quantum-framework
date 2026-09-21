package com.e2eq.ontology.policy;

import com.e2eq.ontology.core.InMemoryOntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuleVocabularyValidatorEdgeFilterTest {

    private static OntologyRegistry registryWith(String... terms) {
        Map<String, OntologyRegistry.PropertyDef> properties = new java.util.HashMap<>();
        for (String t : terms) {
            properties.put(t, new OntologyRegistry.PropertyDef(t,
                    Optional.empty(), Optional.empty(), false, Optional.empty(),
                    false, false, false, Set.of(), false));
        }
        return new InMemoryOntologyRegistry(new OntologyRegistry.TBox(Map.of(), properties, List.of()));
    }

    @Test
    void acceptsEdgeFilterWithDeclaredProperties() {
        OntologyRegistry reg = registryWith("assignedTo", "role", "status");
        RuleVocabularyValidator validator = new RuleVocabularyValidator(reg);

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("rule-ok", List.of(
                        "hasEdge(\"assignedTo\", ${locId}, { role: \"PRIMARY\" && status: \"ACTIVE\" })"))));

        assertEquals(List.of(), violations);
    }

    @Test
    void rejectsUndeclaredEdgePropertyAsAbsent() {
        OntologyRegistry reg = registryWith("assignedTo", "role");
        RuleVocabularyValidator validator = new RuleVocabularyValidator(reg);

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("rule-undeclared-prop", List.of(
                        "hasEdge(\"assignedTo\", ${locId}, { role: \"PRIMARY\" && unknownProp: \"ACTIVE\" })"))));

        assertEquals(1, violations.size());
        RuleVocabularyValidator.Violation v = violations.get(0);
        assertEquals("unknownProp", v.predicate());
        assertEquals("hasEdge", v.helperFunction());
        assertEquals(RuleVocabularyValidator.Reason.ABSENT, v.reason());
        assertEquals("edge property", v.termType());
        assertTrue(v.toString().contains("unknown ontology edge property 'unknownProp'"));
    }

    @Test
    void rejectsUnadmittedEdgePropertyAsProvisional() {
        OntologyRegistry reg = registryWith("assignedTo", "role", "status");
        // Only assignedTo and role admitted; status is provisional
        RuleVocabularyValidator validator = new RuleVocabularyValidator(reg, Set.of("assignedTo", "role"));

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("rule-prov-prop", List.of(
                        "hasEdge(\"assignedTo\", ${locId}, { role: \"PRIMARY\", status: \"ACTIVE\" })"))));

        assertEquals(1, violations.size());
        RuleVocabularyValidator.Violation v = violations.get(0);
        assertEquals("status", v.predicate());
        assertEquals(RuleVocabularyValidator.Reason.PROVISIONAL, v.reason());
        assertEquals("edge property", v.termType());
        assertTrue(v.toString().contains("provisional ontology edge property 'status'"));
        assertTrue(v.toString().contains(":promote"));
    }

    @Test
    void failClosedRejectsDeclaredEdgePropertiesAsProvisional() {
        OntologyRegistry reg = registryWith("assignedTo", "role");
        RuleVocabularyValidator validator = RuleVocabularyValidator.failClosed(reg);

        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("rule-fc", List.of(
                        "hasEdge(\"assignedTo\", ${locId}, { role: \"PRIMARY\" })"))));

        // Both predicate and property should fail closed (provisional)
        assertEquals(2, violations.size());
        assertTrue(violations.stream().allMatch(v -> v.reason() == RuleVocabularyValidator.Reason.PROVISIONAL));
    }

    @Test
    void ignoresRootEdgeFieldsAndOperatorKeys() {
        OntologyRegistry reg = registryWith("assignedTo", "role");
        RuleVocabularyValidator validator = new RuleVocabularyValidator(reg);

        // Root fields: src, dst, inferred, dataDomain, props.role
        List<RuleVocabularyValidator.Violation> violations = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("rule-root-fields", List.of(
                        "hasEdge(\"assignedTo\", ${locId}, { src: \"u1\", inferred: false, \"props.role\": \"PRIMARY\", $and: [] })"))));

        assertEquals(List.of(), violations);
    }

    @Test
    void validatesCallbackFunctionPropertyAccess() {
        OntologyRegistry reg = registryWith("assignedTo", "role");
        RuleVocabularyValidator validator = new RuleVocabularyValidator(reg);

        // edge.role declared -> ok
        List<RuleVocabularyValidator.Violation> ok = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("cb-ok", List.of(
                        "hasEdge(\"assignedTo\", locId, edge => edge.role === 'PRIMARY')"))));
        assertEquals(List.of(), ok);

        // edge.badProp undeclared -> violation
        List<RuleVocabularyValidator.Violation> bad = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("cb-bad", List.of(
                        "hasEdge(\"assignedTo\", locId, edge => edge.badProp === 'VAL')"))));
        assertEquals(1, bad.size());
        assertEquals("badProp", bad.get(0).predicate());
        assertEquals(RuleVocabularyValidator.Reason.ABSENT, bad.get(0).reason());
    }

    @Test
    void validatesHasIncomingEdgePropertyFilter() {
        OntologyRegistry reg = registryWith("canAccessLocation", "clearanceLevel");
        RuleVocabularyValidator validator = new RuleVocabularyValidator(reg);

        List<RuleVocabularyValidator.Violation> ok = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("inc-ok", List.of(
                        "hasIncomingEdge(\"canAccessLocation\", principalId, { clearanceLevel: 'SECRET' })"))));
        assertEquals(List.of(), ok);

        List<RuleVocabularyValidator.Violation> bad = validator.validate(List.of(
                new RuleVocabularyValidator.RuleSource("inc-bad", List.of(
                        "hasIncomingEdge(\"canAccessLocation\", principalId, { unkLevel: 'SECRET' })"))));
        assertEquals(1, bad.size());
        assertEquals("unkLevel", bad.get(0).predicate());
    }

    @Test
    void validateOrThrowFailsFastOnEdgePropertyViolation() {
        OntologyRegistry reg = registryWith("assignedTo");
        RuleVocabularyValidator validator = new RuleVocabularyValidator(reg);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                validator.validateOrThrow(List.of(
                        new RuleVocabularyValidator.RuleSource("throw-rule", List.of(
                                "hasEdge(\"assignedTo\", locId, { nonExistentProp: 'X' })")))));

        assertTrue(ex.getMessage().contains("nonExistentProp"));
        assertTrue(ex.getMessage().contains("throw-rule"));
    }
}
