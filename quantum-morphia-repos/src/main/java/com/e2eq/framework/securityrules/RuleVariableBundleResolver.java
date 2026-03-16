package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import io.quarkus.logging.Log;
import jakarta.enterprise.inject.Instance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.HashMap;
import java.util.Map;

final class RuleVariableBundleResolver {

    private final Instance<AccessListResolver> resolvers;

    RuleVariableBundleResolver(Instance<AccessListResolver> resolvers) {
        this.resolvers = resolvers;
    }

    MorphiaUtils.VariableBundle resolveVariableBundle(
            @Valid @NotNull(message = "Principal Context can not be null") PrincipalContext pcontext,
            @Valid @NotNull(message = "Resource Context can not be null") ResourceContext rcontext,
            Class<? extends UnversionedBaseModel> modelClass) {
        Map<String, Object> extraObjects = new HashMap<>();
        if (resolvers != null) {
            for (AccessListResolver resolver : resolvers) {
                try {
                    boolean supported = resolver.supports(pcontext, rcontext, modelClass);
                    if (Log.isDebugEnabled()) {
                        Log.debugf("AccessListResolver '%s' key='%s' supports(area=%s, domain=%s, action=%s, model=%s) = %s",
                                resolver.getClass().getSimpleName(), resolver.key(),
                                rcontext != null ? rcontext.getArea() : "null",
                                rcontext != null ? rcontext.getFunctionalDomain() : "null",
                                rcontext != null ? rcontext.getAction() : "null",
                                modelClass != null ? modelClass.getSimpleName() : "null",
                                supported);
                    }
                    if (supported) {
                        extraObjects.put(resolver.key(), resolver.resolve(pcontext, rcontext, modelClass));
                    }
                } catch (Exception e) {
                    Log.warnf(e, "AccessListResolver '%s' failed; continuing without it", resolver.getClass().getName());
                }
            }
        }

        MorphiaUtils.VariableBundle bundle = MorphiaUtils.buildVariableBundle(pcontext, rcontext, extraObjects);
        if (Log.isDebugEnabled()) {
            Log.debugf("resolveVariableBundle: customProperties=%s, extraObjects=%s, final objects keys=%s",
                    pcontext != null ? pcontext.getCustomProperties().keySet() : "null",
                    extraObjects.keySet(),
                    bundle.objects.keySet());
        }
        return bundle;
    }
}
