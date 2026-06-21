package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.DependsOn;
import com.e2eq.ontology.annotations.OntologyClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComputedEdgeStalenessValidatorTest {

    @OntologyClass(id = "Src") static class Src {}
    static class Dep1 {}
    static class Dep2 {}

    /** No dependencies declared → always OK. */
    static class NoDeps extends ComputedEdgeProvider<Src> {
        @Override public Class<Src> getSourceType() { return Src.class; }
        @Override public String getPredicate() { return "p"; }
        @Override public String getTargetTypeName() { return "T"; }
        @Override protected Set<ComputedTarget> computeTargets(ComputationContext c, Src s) { return Set.of(); }
    }

    /** Declares deps via override only, no override of getAffectedSourceIds → risk. */
    static class DepsNoHelp extends ComputedEdgeProvider<Src> {
        @Override public Class<Src> getSourceType() { return Src.class; }
        @Override public String getPredicate() { return "p"; }
        @Override public String getTargetTypeName() { return "T"; }
        @Override public Set<Class<?>> getDependencyTypes() { return Set.of(Dep1.class, Dep2.class); }
        @Override protected Set<ComputedTarget> computeTargets(ComputationContext c, Src s) { return Set.of(); }
    }

    /** Overrides getAffectedSourceIds → no risk even though deps declared. */
    static class DepsWithOverride extends ComputedEdgeProvider<Src> {
        @Override public Class<Src> getSourceType() { return Src.class; }
        @Override public String getPredicate() { return "p"; }
        @Override public String getTargetTypeName() { return "T"; }
        @Override public Set<Class<?>> getDependencyTypes() { return Set.of(Dep1.class); }
        @Override public Set<String> getAffectedSourceIds(ComputationContext c, Class<?> t, String id) {
            return Set.of();
        }
        @Override protected Set<ComputedTarget> computeTargets(ComputationContext c, Src s) { return Set.of(); }
    }

    /** @DependsOn declares the type and a non-empty via → no risk. */
    @DependsOn(type = Dep1.class, via = "depField")
    static class DepsWithAnnotationVia extends ComputedEdgeProvider<Src> {
        @Override public Class<Src> getSourceType() { return Src.class; }
        @Override public String getPredicate() { return "p"; }
        @Override public String getTargetTypeName() { return "T"; }
        @Override protected Set<ComputedTarget> computeTargets(ComputationContext c, Src s) { return Set.of(); }
    }

    /** @DependsOn without via → risk (no inverse-query hint). */
    @DependsOn(type = Dep1.class)
    static class DepsAnnotationNoVia extends ComputedEdgeProvider<Src> {
        @Override public Class<Src> getSourceType() { return Src.class; }
        @Override public String getPredicate() { return "p"; }
        @Override public String getTargetTypeName() { return "T"; }
        @Override protected Set<ComputedTarget> computeTargets(ComputationContext c, Src s) { return Set.of(); }
    }

    @Test
    void noDepsAlwaysOk() {
        assertTrue(ComputedEdgeStalenessValidator.validate(new NoDeps()).ok());
    }

    @Test
    void unhelpedDepsReportRisk() {
        var r = ComputedEdgeStalenessValidator.validate(new DepsNoHelp());
        assertFalse(r.ok());
        assertEquals(2, r.risks().size());
    }

    @Test
    void overrideSilencesAllDeps() {
        assertTrue(ComputedEdgeStalenessValidator.validate(new DepsWithOverride()).ok());
    }

    @Test
    void annotationWithViaIsSufficient() {
        assertTrue(ComputedEdgeStalenessValidator.validate(new DepsWithAnnotationVia()).ok());
    }

    @Test
    void annotationWithoutViaStillRisky() {
        var r = ComputedEdgeStalenessValidator.validate(new DepsAnnotationNoVia());
        assertFalse(r.ok());
        Optional<ComputedEdgeStalenessValidator.StalenessRisk> first = r.risks().stream().findFirst();
        assertTrue(first.isPresent());
        assertEquals(Dep1.class, first.get().dependencyType());
    }

    @Test
    void dependencyTypesDerivedFromAnnotations() {
        Set<Class<?>> deps = new DepsWithAnnotationVia().getDependencyTypes();
        assertEquals(Set.of(Dep1.class), deps);
    }

    @Test
    void multipleAnnotationsAggregated() {
        @DependsOn(type = Dep1.class, via = "f1")
        @DependsOn(type = Dep2.class, via = "f2")
        class Multi extends ComputedEdgeProvider<Src> {
            @Override public Class<Src> getSourceType() { return Src.class; }
            @Override public String getPredicate() { return "p"; }
            @Override public String getTargetTypeName() { return "T"; }
            @Override protected Set<ComputedTarget> computeTargets(ComputationContext c, Src s) { return Set.of(); }
        }

        Multi m = new Multi();
        assertEquals(Set.of(Dep1.class, Dep2.class), m.getDependencyTypes());
        assertTrue(ComputedEdgeStalenessValidator.validate(m).ok());

        List<DependsOn> decls = m.dependsOnDeclarations();
        assertEquals(2, decls.size());
    }
}
