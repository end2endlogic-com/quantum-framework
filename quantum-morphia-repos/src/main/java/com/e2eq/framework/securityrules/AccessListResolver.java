package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;

import java.util.Collection;

/**
 * SPI for resolving access-controlled lists (e.g., IDs) for a principal/resource context.
 */
public interface AccessListResolver {
    /**
     * The variable key to publish into the variable bundle (e.g., "accessibleCustomerIds").
     */
    String key();

    /**
     * Whether this resolver applies for the given context and model type.
     */
    boolean supports(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass);

    /**
     * Resolve the collection for this context and model type.
     */
    Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass);
}
