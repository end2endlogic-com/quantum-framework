package com.e2eq.framework.actionenablement.contrib;

import com.e2eq.framework.actionenablement.model.DependencyCheckRef;
import com.e2eq.framework.actionenablement.model.ScopedActionRef;
import com.e2eq.framework.actionenablement.model.ScopedActionRequirement;
import com.e2eq.framework.actionenablement.spi.ScopedActionRequirementContributor;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;

@ApplicationScoped
public class FrameworkActionEnablementManifestContributor implements ScopedActionRequirementContributor {

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public Collection<ScopedActionRequirement> requirements() {
        return List.of(
                requirement(
                        "system",
                        "action-enablement",
                        "check",
                        "Check Scoped Action Enablement",
                        "Evaluates whether a scoped action is allowed, feature-enabled, and operationally ready."
                ),
                requirement(
                        "system",
                        "action-enablement",
                        "view",
                        "View Scoped Action Enablement Manifest",
                        "Lists the registered scoped action requirements contributed by the framework and applications."
                )
        );
    }

    private ScopedActionRequirement requirement(
            String area,
            String functionalDomain,
            String action,
            String displayName,
            String description
    ) {
        return ScopedActionRequirement.builder()
                .scopedAction(ScopedActionRef.builder()
                        .area(area)
                        .functionalDomain(functionalDomain)
                        .action(action)
                        .build())
                .displayName(displayName)
                .description(description)
                .dependencies(List.of(
                        DependencyCheckRef.builder()
                                .type("permission")
                                .build()
                ))
                .build();
    }
}
