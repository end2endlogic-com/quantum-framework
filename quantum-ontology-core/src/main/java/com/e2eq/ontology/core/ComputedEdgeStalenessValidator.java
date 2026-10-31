package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.DependsOn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-logic validator for computed-edge providers.
 *
 * <p>For each declared dependency type, the provider must either override
 * {@link ComputedEdgeProvider#getAffectedSourceIds} or carry a
 * {@link DependsOn} with a non-empty {@code via} hint. Otherwise changes to
 * the dependency cannot invalidate the computed edges and they will go stale.</p>
 *
 * <p>Lives in core (no CDI) so it can be unit-tested without a container.</p>
 */
public final class ComputedEdgeStalenessValidator {

    private ComputedEdgeStalenessValidator() {}

    /** Result of validating one provider. */
    public record ValidationResult(
            String providerId,
            List<StalenessRisk> risks
    ) {
        public boolean ok() { return risks.isEmpty(); }
    }

    public record StalenessRisk(String providerId, Class<?> dependencyType, String reason) {}

    public static ValidationResult validate(ComputedEdgeProvider<?> provider) {
        List<StalenessRisk> risks = new ArrayList<>();
        Set<Class<?>> deps = provider.getDependencyTypes();
        if (deps.isEmpty()) {
            return new ValidationResult(provider.getProviderId(), risks);
        }

        boolean overrides = overridesGetAffectedSourceIds(provider.getClass());

        Set<Class<?>> hasUsableHint = new HashSet<>();
        for (DependsOn d : provider.dependsOnDeclarations()) {
            if (d.via() != null && !d.via().isBlank()) hasUsableHint.add(d.type());
        }

        for (Class<?> dep : deps) {
            if (overrides || hasUsableHint.contains(dep)) continue;
            risks.add(new StalenessRisk(
                    provider.getProviderId(), dep,
                    "neither overrides getAffectedSourceIds() nor has @DependsOn(via=...)"));
        }
        return new ValidationResult(provider.getProviderId(), risks);
    }

    static boolean overridesGetAffectedSourceIds(Class<?> providerClass) {
        Class<?> c = providerClass;
        while (c != null && c != ComputedEdgeProvider.class) {
            try {
                c.getDeclaredMethod("getAffectedSourceIds",
                        ComputedEdgeProvider.ComputationContext.class,
                        Class.class, String.class);
                return true;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return false;
    }
}
