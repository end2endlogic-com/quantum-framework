package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.FieldPolicyEnforcer;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.runtime.RuleContext;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

/**
 * REST-egress enforcement of field-level policy (defense-in-depth layer).
 *
 * The primary enforcement is at the datastore (Mongo projection / datasource
 * adapter masking) — this interceptor re-applies the same resolved exclusion
 * set at serialization time, catching what no datastore layer can see:
 * computed getters, reference-expanded fields, and responses assembled
 * outside a secured repo. One policy source, every door guarded.
 *
 * Applies only to platform entities (UnversionedBaseModel, or collections of
 * them); non-entity payloads (health, metrics, DTOs) pass through untouched.
 * When fields were masked, the X-Field-Policy header lists the excluded paths
 * so UIs can render "restricted" rather than "empty".
 */
@Provider
public class FieldPolicyResponseInterceptor implements WriterInterceptor {

    public static final String FIELD_POLICY_HEADER = "X-Field-Policy";

    @Inject
    RuleContext ruleContext;

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        Object entity = context.getEntity();
        if (isEntityPayload(entity)
                && SecurityContext.getPrincipalContext().isPresent()
                && SecurityContext.getResourceContext().isPresent()) {
            Set<String> excluded = ruleContext.getExcludedFieldPaths(
                    SecurityContext.getPrincipalContext().get(),
                    SecurityContext.getResourceContext().get());
            if (!excluded.isEmpty()) {
                FieldPolicyEnforcer.mask(entity, excluded);
                context.getHeaders().putSingle(FIELD_POLICY_HEADER, String.join(",", excluded));
                if (Log.isDebugEnabled()) {
                    Log.debugf("Field policy masked %s on %s", excluded, entity.getClass().getSimpleName());
                }
            }
        }
        context.proceed();
    }

    private static boolean isEntityPayload(Object entity) {
        if (entity instanceof UnversionedBaseModel) {
            return true;
        }
        if (entity instanceof Collection<?> many) {
            for (Object item : many) {
                if (item != null) {
                    return item instanceof UnversionedBaseModel;
                }
            }
        }
        return false;
    }
}
