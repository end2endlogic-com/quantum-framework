package com.e2eq.framework.actionenablement.runtime.resolver;

import com.e2eq.framework.actionenablement.model.DependencyCheckRef;
import com.e2eq.framework.actionenablement.model.EnablementBlocker;
import com.e2eq.framework.actionenablement.model.EnablementImpact;
import com.e2eq.framework.actionenablement.runtime.DependencyResolutionResult;
import com.e2eq.framework.actionenablement.runtime.EnablementEvaluationContext;
import com.e2eq.framework.actionenablement.spi.ActionDependencyResolver;
import com.e2eq.framework.model.general.FeatureFlag;
import com.e2eq.framework.model.persistent.morphia.FeatureFlagRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class FeatureFlagActionDependencyResolver implements ActionDependencyResolver {

    @Inject
    FeatureFlagRepo featureFlagRepo;

    @Override
    public String supportsType() {
        return "feature-flag";
    }

    @Override
    public DependencyResolutionResult evaluate(DependencyCheckRef dependency, EnablementEvaluationContext context) {
        String refName = dependency.getRefName() == null ? "" : dependency.getRefName().trim();
        if (refName.isEmpty()) {
            return DependencyResolutionResult.blocked(EnablementBlocker.builder()
                    .impact(EnablementImpact.ENABLED)
                    .type("feature-flag")
                    .code("feature-flag-ref-missing")
                    .message("Feature flag dependency is missing a refName.")
                    .severity("error")
                    .build());
        }

        Optional<FeatureFlag> featureFlag = featureFlagRepo.findByRefName(refName, context.getRealm());
        if (featureFlag.isEmpty()) {
            return DependencyResolutionResult.blocked(EnablementBlocker.builder()
                    .impact(EnablementImpact.ENABLED)
                    .type("feature-flag")
                    .code("feature-flag-missing")
                    .message("Feature flag '" + refName + "' was not found in realm '" + context.getRealm() + "'.")
                    .severity("error")
                    .metadata(Map.of("refName", refName, "realm", context.getRealm()))
                    .build());
        }

        if (!featureFlag.get().isEnabled()) {
            return DependencyResolutionResult.blocked(EnablementBlocker.builder()
                    .impact(EnablementImpact.ENABLED)
                    .type("feature-flag")
                    .code("feature-flag-disabled")
                    .message("Feature flag '" + refName + "' is disabled.")
                    .severity("warn")
                    .metadata(Map.of("refName", refName, "realm", context.getRealm()))
                    .build());
        }

        return DependencyResolutionResult.satisfied();
    }
}
