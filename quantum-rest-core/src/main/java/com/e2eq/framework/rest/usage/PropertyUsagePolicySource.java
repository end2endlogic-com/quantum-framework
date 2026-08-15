package com.e2eq.framework.rest.usage;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/** Default policy source backed by the validated quantum.rest.usage.* property manifest. */
@DefaultBean
@ApplicationScoped
public class PropertyUsagePolicySource implements UsagePolicySource {

    private final UsageGovernanceConfig config;

    @Inject
    public PropertyUsagePolicySource(UsageGovernanceConfig config) {
        this.config = config;
    }

    @Override
    public Optional<UsagePolicy> policyFor(UsageRequest request) {
        UsagePolicy policy = config.policy();
        return policy.appliesTo(request.endpoint()) ? Optional.of(policy) : Optional.empty();
    }
}
