package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;

/**
 * Compatibility bridge for applications that import the historical
 * {@code com.e2eq.framework.rest.resources.BaseResource} type.
 *
 * <p>New REST modules should depend on {@code quantum-rest-core} and extend
 * {@link com.e2eq.framework.rest.core.BaseResource} directly.</p>
 */
public class BaseResource<T extends UnversionedBaseModel, R extends BaseMorphiaRepo<T>>
        extends com.e2eq.framework.rest.core.BaseResource<T, R> {

    protected BaseResource(R repo) {
        super(repo);
    }
}
