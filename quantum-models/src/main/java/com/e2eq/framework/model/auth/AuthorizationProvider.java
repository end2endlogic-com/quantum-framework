package com.e2eq.framework.model.auth;

import com.e2eq.framework.model.securityrules.EvalMode;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;

/**
 * Optional SPI for authentication providers that also own authorization policy.
 *
 * <p>When the configured {@link AuthProvider} implements this interface, data-plane
 * services delegate policy evaluation to it instead of reading a local policy
 * repository. Providers that do not implement it retain the framework's embedded
 * {@code RuleContext}/{@code PolicyRepo} behavior.</p>
 */
public interface AuthorizationProvider extends AuthProvider {

    /**
     * Evaluate the caller's access to a resource through the provider's policy plane.
     * Implementations must fail closed when the policy service cannot make a decision.
     */
    SecurityCheckResponse checkRules(
            PrincipalContext principalContext,
            ResourceContext resourceContext,
            String modelClass,
            Object resourceInstance,
            RuleEffect defaultFinalEffect,
            EvalMode evalMode);
}
