package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;

/**
 * Resolves the DataDomain to stamp on a newly created record.
 * Default behavior should always fallback to the principal's credential domain
 * to preserve current behavior when no policy is present.
 */
public interface DataDomainResolver {
    /**
     * Resolve the DataDomain for a create operation.
     * @param functionalArea the model's functional area
     * @param functionalDomain the model's functional domain
     * @return a non-null DataDomain; if no policy applies, returns the principal's domain
     */
    DataDomain resolveForCreate(String functionalArea, String functionalDomain);
}
