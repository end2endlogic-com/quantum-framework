package com.e2eq.framework.actionenablement.runtime.resolver;

import com.e2eq.framework.actionenablement.model.DependencyCheckRef;
import com.e2eq.framework.actionenablement.model.EnablementBlocker;
import com.e2eq.framework.actionenablement.model.EnablementImpact;
import com.e2eq.framework.actionenablement.runtime.DependencyResolutionResult;
import com.e2eq.framework.actionenablement.runtime.EnablementEvaluationContext;
import com.e2eq.framework.actionenablement.spi.ActionDependencyResolver;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ConfigPropertyActionDependencyResolver implements ActionDependencyResolver {

    @Override
    public String supportsType() {
        return "setting-present";
    }

    @Override
    public DependencyResolutionResult evaluate(DependencyCheckRef dependency, EnablementEvaluationContext context) {
        String propertyName = dependency.getRefName() == null ? "" : dependency.getRefName().trim();
        if (propertyName.isEmpty()) {
            return DependencyResolutionResult.blocked(EnablementBlocker.builder()
                    .impact(EnablementImpact.READY)
                    .type("setting-present")
                    .code("setting-name-missing")
                    .message("Setting dependency is missing a property name.")
                    .severity("error")
                    .build());
        }

        Optional<String> value = ConfigProvider.getConfig().getOptionalValue(propertyName, String.class);
        if (value.isPresent() && !value.get().isBlank()) {
            return DependencyResolutionResult.satisfied();
        }

        return DependencyResolutionResult.blocked(EnablementBlocker.builder()
                .impact(EnablementImpact.READY)
                .type("setting-present")
                .code("setting-missing")
                .message("Configuration setting '" + propertyName + "' is not set.")
                .severity("error")
                .metadata(Map.of("property", propertyName))
                .build());
    }
}
