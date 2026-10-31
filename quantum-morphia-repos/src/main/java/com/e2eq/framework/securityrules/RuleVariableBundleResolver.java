package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.security.DataDomainResolver;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import io.quarkus.logging.Log;
import jakarta.enterprise.inject.Instance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;

final class RuleVariableBundleResolver {

    private final Instance<AccessListResolver> resolvers;
    private final Instance<DataDomainResolver> dataDomainResolvers;

    RuleVariableBundleResolver(Instance<AccessListResolver> resolvers) {
        this(resolvers, null);
    }

    RuleVariableBundleResolver(Instance<AccessListResolver> resolvers, Instance<DataDomainResolver> dataDomainResolvers) {
        this.resolvers = resolvers;
        this.dataDomainResolvers = dataDomainResolvers;
    }

    MorphiaUtils.VariableBundle resolveVariableBundle(
            @Valid @NotNull(message = "Principal Context can not be null") PrincipalContext pcontext,
            @Valid @NotNull(message = "Resource Context can not be null") ResourceContext rcontext,
            Class<? extends UnversionedBaseModel> modelClass) {
        Map<String, Object> extraObjects = new HashMap<>();
        Set<String> failedKeys = new LinkedHashSet<>();
        if (resolvers != null) {
            for (AccessListResolver resolver : resolvers) {
                String key = null;
                try {
                    key = resolver.key();
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
                        extraObjects.put(key, resolver.resolve(pcontext, rcontext, modelClass));
                    }
                } catch (Exception e) {
                    // Fail closed: a failed resolver must never look like "variable not applicable".
                    // The key is recorded so every rule filter referencing it contributes a
                    // match-nothing filter (reads) or a deny (writes) instead of being dropped.
                    if (key != null) {
                        failedKeys.add(key);
                    }
                    Log.warnf(e, "AccessListResolver '%s' (variable '%s') failed; rule filters referencing it will fail closed",
                            resolver.getClass().getName(), key);
                }
            }
        }

        DataDomainResolver ddResolver = (dataDomainResolvers != null && !dataDomainResolvers.isUnsatisfied()) ? dataDomainResolvers.get() : null;
        MorphiaUtils.VariableBundle built = MorphiaUtils.buildVariableBundle(pcontext, rcontext, extraObjects, modelClass, ddResolver);
        MorphiaUtils.VariableBundle bundle = built;
        if (!failedKeys.isEmpty()) {
            // Remove any value a failed key might otherwise pick up (e.g. a standard or custom
            // property of the same name) so it cannot be used as if the resolver had succeeded.
            Map<String, String> strings = new HashMap<>(built.strings);
            Map<String, Object> objects = new HashMap<>(built.objects);
            for (String failed : failedKeys) {
                strings.remove(failed);
                objects.remove(failed);
            }
            bundle = new MorphiaUtils.VariableBundle(strings, objects, failedKeys);
        }
        if (Log.isDebugEnabled()) {
            Log.debugf("resolveVariableBundle: customProperties=%s, extraObjects=%s, final objects keys=%s",
                    pcontext != null ? pcontext.getCustomProperties().keySet() : "null",
                    extraObjects.keySet(),
                    bundle.objects.keySet());
        }
        return bundle;
    }
}
