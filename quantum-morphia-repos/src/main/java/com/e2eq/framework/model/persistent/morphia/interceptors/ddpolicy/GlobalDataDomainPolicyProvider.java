package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.security.DataDomainPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple in-memory provider for a global DataDomainPolicy.
 * By default, no policy is configured (Optional.empty()), preserving current behavior.
 * Applications can set a global policy at runtime if desired.
 */
@ApplicationScoped
public class GlobalDataDomainPolicyProvider {

    private final AtomicReference<DataDomainPolicy> globalPolicy = new AtomicReference<>(null);

    public Optional<DataDomainPolicy> getPolicy() {
        return Optional.ofNullable(globalPolicy.get());
    }

    public void setPolicy(DataDomainPolicy policy) {
        globalPolicy.set(policy);
    }
}
