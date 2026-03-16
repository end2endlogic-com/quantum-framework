package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.runtime.RuleContext;
import dev.morphia.query.filters.Filter;
import io.quarkus.logging.Log;

import java.util.List;

final class RepoSecurityFilterBuilder {

    private final RepoSecurityContextResolver securityContextResolver;
    private final RuleContext ruleContext;

    RepoSecurityFilterBuilder(RepoSecurityContextResolver securityContextResolver, RuleContext ruleContext) {
        this.securityContextResolver = securityContextResolver;
        this.ruleContext = ruleContext;
    }

    List<Filter> buildSecuredFilters(List<Filter> filters, Class<? extends UnversionedBaseModel> modelClass) {
        if (SecurityContext.getResourceContext().isEmpty() || SecurityContext.getPrincipalContext().isEmpty()) {
            securityContextResolver.ensureSecurityContextFromIdentity();
        }

        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            return ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get(), modelClass);
        }

        boolean missingPrincipalContext = SecurityContext.getPrincipalContext().isEmpty();
        boolean missingResourceContext = SecurityContext.getResourceContext().isEmpty();
        String missingContext = missingPrincipalContext && missingResourceContext
                ? "PrincipalContext and ResourceContext"
                : missingPrincipalContext ? "PrincipalContext" : "ResourceContext";
        Log.warnf("SecurityContext is not set for %s; missing %s",
                modelClass != null ? modelClass.getSimpleName() : "<unknown>", missingContext);
        throw new RuntimeException("SecurityContext is not set in thread; missing " + missingContext + ". Check security configuration.");
    }

    Filter[] getFilterArray(List<Filter> filters, Class<? extends UnversionedBaseModel> modelClass) {
        if (SecurityContext.isIgnoringRules()) {
            if (Log.isDebugEnabled()) {
                Log.debugf("getFilterArray: ignoring rules mode active, skipping rule evaluation for %s",
                        modelClass != null ? modelClass.getSimpleName() : "null");
            }
            return filters.toArray(new Filter[0]);
        }

        List<Filter> securedFilters = buildSecuredFilters(filters, modelClass);
        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            if (Log.isDebugEnabled()) {
                Log.debugf("getFilterArray for %s security context: %s", SecurityContext.getPrincipalContext().get().getUserId(), securedFilters);
            }
        }
        return securedFilters.toArray(new Filter[0]);
    }
}
