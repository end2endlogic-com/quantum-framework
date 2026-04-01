package com.e2eq.framework.actionenablement.runtime;

import com.e2eq.framework.actionenablement.model.DependencyCheckRef;
import com.e2eq.framework.actionenablement.model.EnablementBlocker;
import com.e2eq.framework.actionenablement.model.EnablementImpact;
import com.e2eq.framework.actionenablement.model.ScopedActionEnablementRequest;
import com.e2eq.framework.actionenablement.model.ScopedActionEnablementStatus;
import com.e2eq.framework.actionenablement.model.ScopedActionRef;
import com.e2eq.framework.actionenablement.model.ScopedActionRequirement;
import com.e2eq.framework.actionenablement.spi.ActionDependencyResolver;
import com.e2eq.framework.actionenablement.spi.ScopedActionRequirementRegistry;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ScopedActionEnablementService {

    @Inject
    ScopedActionRequirementRegistry requirementRegistry;

    @Inject
    Instance<ActionDependencyResolver> resolvers;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    SecurityIdentity securityIdentity;

    public List<ScopedActionEnablementStatus> evaluate(ScopedActionEnablementRequest request) {
        List<ScopedActionEnablementStatus> results = new ArrayList<>();
        for (ScopedActionRef action : CollectionUtils.safeList(request.getActions())) {
            if (action == null) {
                continue;
            }
            results.add(evaluateAction(request, action));
        }
        return results;
    }

    public List<ScopedActionRequirement> listManifest() {
        return requirementRegistry.list();
    }

    private ScopedActionEnablementStatus evaluateAction(ScopedActionEnablementRequest request, ScopedActionRef action) {
        Optional<ScopedActionRequirement> registered = requirementRegistry.find(action);
        ScopedActionRequirement requirement = registered.orElseGet(() -> syntheticRequirement(action));
        EnablementEvaluationContext context = buildContext(request, action);
        Map<String, ActionDependencyResolver> resolverIndex = indexResolvers();
        List<EnablementBlocker> blockers = new ArrayList<>();

        for (DependencyCheckRef dependency : CollectionUtils.safeList(requirement.getDependencies())) {
            if (dependency == null) {
                continue;
            }
            ActionDependencyResolver resolver = resolverIndex.get(dependency.normalizedType());
            if (resolver == null) {
                blockers.add(EnablementBlocker.builder()
                        .impact(EnablementImpact.READY)
                        .type(dependency.getType())
                        .code("unsupported-dependency-type")
                        .message("No resolver is registered for dependency type '" + dependency.getType() + "'.")
                        .severity("error")
                        .build());
                continue;
            }

            DependencyResolutionResult result = resolver.evaluate(dependency, context);
            if (result != null && !result.isSatisfied()) {
                blockers.addAll(CollectionUtils.safeList(result.getBlockers()));
            }
        }

        if (registered.isEmpty()) {
            blockers.add(EnablementBlocker.builder()
                    .impact(EnablementImpact.READY)
                    .type("manifest")
                    .code("manifest-missing")
                    .message("No scoped action requirement manifest is registered for " + action.toUriString() + ".")
                    .severity("warn")
                    .build());
        }

        boolean allowed = blockers.stream().noneMatch(blocker -> blocker.getImpact() == EnablementImpact.ALLOWED);
        boolean enabled = blockers.stream().noneMatch(blocker -> blocker.getImpact() == EnablementImpact.ENABLED);
        boolean ready = blockers.stream().noneMatch(blocker -> blocker.getImpact() == EnablementImpact.READY);

        return ScopedActionEnablementStatus.builder()
                .scopedAction(action)
                .allowed(allowed)
                .enabled(enabled)
                .ready(ready)
                .usable(allowed && enabled && ready)
                .blockers(blockers)
                .build();
    }

    private EnablementEvaluationContext buildContext(ScopedActionEnablementRequest request, ScopedActionRef action) {
        String identity = resolveIdentity(request.getIdentity());
        String realm = resolveRealm(request.getRealm());
        DataDomain defaultDataDomain = securityUtils.getDefaultDataDomain();

        String org = firstNonBlank(
                request.getOrgRefName(),
                SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getOrgRefName()).orElse(null),
                defaultDataDomain.getOrgRefName()
        );
        String account = firstNonBlank(
                request.getAccountNumber(),
                SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getAccountNum()).orElse(null),
                defaultDataDomain.getAccountNum()
        );
        String tenant = firstNonBlank(
                request.getTenantId(),
                SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getTenantId()).orElse(null),
                defaultDataDomain.getTenantId()
        );
        int dataSegment = request.getDataSegment() != null
                ? request.getDataSegment()
                : SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getDataSegment()).orElse(defaultDataDomain.getDataSegment());

        String ownerId = firstNonBlank(
                request.getOwnerId(),
                SecurityContext.getResourceContext().map(ResourceContext::getOwnerId).orElse(null),
                SecurityContext.getPrincipalContext().map(PrincipalContext::getUserId).orElse(null),
                identity
        );

        return EnablementEvaluationContext.builder()
                .identity(identity)
                .realm(realm)
                .roles(request.getRoles())
                .scope(firstNonBlank(request.getScope(), "api"))
                .dataDomain(new DataDomain(org, account, tenant, dataSegment, ownerId))
                .scopedAction(action)
                .build();
    }

    private String resolveIdentity(String requestedIdentity) {
        if (requestedIdentity != null && !requestedIdentity.isBlank()) {
            return requestedIdentity.trim();
        }

        try {
            Object userIdAttr = securityIdentity.getAttribute("userId");
            if (userIdAttr instanceof String userId && !userId.isBlank()) {
                return userId;
            }
        }
        catch (Exception ignored) {
        }

        if (securityIdentity.getPrincipal() != null && securityIdentity.getPrincipal().getName() != null) {
            return securityIdentity.getPrincipal().getName();
        }

        return securityUtils.getDefaultDataDomain().getOwnerId();
    }

    private String resolveRealm(String requestedRealm) {
        return firstNonBlank(
                requestedRealm,
                SecurityContext.getPrincipalContext().map(PrincipalContext::getDefaultRealm).orElse(null),
                securityUtils.getDefaultDomainContext().getDefaultRealm()
        );
    }

    private ScopedActionRequirement syntheticRequirement(ScopedActionRef action) {
        return ScopedActionRequirement.builder()
                .scopedAction(action)
                .displayName(action.toUriString())
                .description("Synthetic fallback requirement when no manifest entry is registered.")
                .dependencies(List.of(DependencyCheckRef.builder().type("permission").build()))
                .build();
    }

    private Map<String, ActionDependencyResolver> indexResolvers() {
        Map<String, ActionDependencyResolver> resolverIndex = new LinkedHashMap<>();
        for (ActionDependencyResolver resolver : resolvers) {
            resolverIndex.put(resolver.supportsType().trim().toLowerCase(), resolver);
        }
        return resolverIndex;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
