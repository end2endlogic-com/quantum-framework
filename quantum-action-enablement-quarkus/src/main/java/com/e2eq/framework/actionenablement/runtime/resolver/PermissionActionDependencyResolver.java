package com.e2eq.framework.actionenablement.runtime.resolver;

import com.e2eq.framework.actionenablement.model.DependencyCheckRef;
import com.e2eq.framework.actionenablement.model.EnablementBlocker;
import com.e2eq.framework.actionenablement.model.EnablementImpact;
import com.e2eq.framework.actionenablement.runtime.DependencyResolutionResult;
import com.e2eq.framework.actionenablement.runtime.EnablementEvaluationContext;
import com.e2eq.framework.actionenablement.spi.ActionDependencyResolver;
import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.model.persistent.morphia.ReceivingApplication;
import com.e2eq.framework.model.securityrules.EvalMode;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.runtime.RuleContext;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class PermissionActionDependencyResolver implements ActionDependencyResolver {

    @Inject
    RuleContext ruleContext;

    @Inject
    IdentityRoleResolver identityRoleResolver;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ReceivingApplication receivingApplication;

    @Override
    public String supportsType() {
        return "permission";
    }

    @Override
    public DependencyResolutionResult evaluate(DependencyCheckRef dependency, EnablementEvaluationContext context) {
        Set<String> resolvedIdentities = new LinkedHashSet<>(identityRoleResolver.resolveRolesForIdentity(
                context.getIdentity(),
                context.getRealm(),
                securityIdentity
        ));
        resolvedIdentities.remove(context.getIdentity());
        if (context.getRoles() != null) {
            resolvedIdentities.addAll(Arrays.asList(context.getRoles()));
        }

        String applicationId = SecurityContext.getPrincipalContext()
                .map(PrincipalContext::getApplicationId)
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(this::configuredApplicationId);

        PrincipalContext principalContext = new PrincipalContext.Builder()
                .withDefaultRealm(context.getRealm())
                .withApplicationId(applicationId)
                .withDataDomain(context.getDataDomain())
                .withUserId(context.getIdentity())
                .withRoles(resolvedIdentities.toArray(new String[0]))
                .withScope(context.getScope())
                .build();

        String normalizedAction = context.getScopedAction().normalizedAction();
        if ("list".equalsIgnoreCase(normalizedAction)) {
            normalizedAction = "LIST";
        }

        ResourceContext resourceContext = new ResourceContext.Builder()
                .withRealm(context.getRealm())
                .withApplicationId(applicationId)
                .withArea(context.getScopedAction().normalizedArea())
                .withFunctionalDomain(context.getScopedAction().normalizedFunctionalDomain())
                .withAction(normalizedAction)
                .withOwnerId(context.getDataDomain().getOwnerId())
                .build();

        SecurityCheckResponse response = ruleContext.checkRules(
                principalContext,
                resourceContext,
                null,
                null,
                RuleEffect.DENY,
                EvalMode.LEGACY
        );

        if (response != null && response.getFinalEffect() == RuleEffect.ALLOW) {
            return DependencyResolutionResult.satisfied();
        }

        EnablementBlocker blocker = EnablementBlocker.builder()
                .impact(EnablementImpact.ALLOWED)
                .type("permission")
                .code("permission-denied")
                .message(buildPermissionMessage(response, context))
                .severity("error")
                .metadata(buildMetadata(response))
                .build();

        return DependencyResolutionResult.blocked(blocker);
    }

    /**
     * No resolved principal application: this application's configured id; only when none is
     * configured, the identity's azp, so application-scoped policies (denies included) still
     * apply. Step 4 deletes the azp fallback once every app configures its id.
     */
    private String configuredApplicationId() {
        String configured = receivingApplication == null ? null : receivingApplication.applicationId();
        if (configured != null) {
            return configured;
        }
        if (securityIdentity == null) {
            return null;
        }
        Object value = securityIdentity.getAttribute("azp");
        if (value == null && securityIdentity.getPrincipal() instanceof org.eclipse.microprofile.jwt.JsonWebToken jwt) {
            value = jwt.getClaim("azp");
        }
        String applicationId = value == null ? null : String.valueOf(value).trim();
        return applicationId == null || applicationId.isEmpty() ? null : applicationId;
    }

    private String buildPermissionMessage(SecurityCheckResponse response, EnablementEvaluationContext context) {
        String prefix = "Policy does not allow " + context.getIdentity() + " to perform " + context.getScopedAction().toUriString() + ".";
        if (response == null) {
            return prefix;
        }
        if (response.getWinningRuleName() != null && !response.getWinningRuleName().isBlank()) {
            return prefix + " Winning rule: " + response.getWinningRuleName() + ".";
        }
        if (response.getNaLabel() != null && !response.getNaLabel().isBlank()) {
            return prefix + " Decision label: " + response.getNaLabel() + ".";
        }
        return prefix;
    }

    private Map<String, Object> buildMetadata(SecurityCheckResponse response) {
        if (response == null) {
            return Map.of();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "decision", response.getDecision());
        putIfPresent(metadata, "scope", response.getDecisionScope());
        putIfPresent(metadata, "winningRuleName", response.getWinningRuleName());
        putIfPresent(metadata, "winningRulePriority", response.getWinningRulePriority());
        putIfPresent(metadata, "naLabel", response.getNaLabel());
        return metadata;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
