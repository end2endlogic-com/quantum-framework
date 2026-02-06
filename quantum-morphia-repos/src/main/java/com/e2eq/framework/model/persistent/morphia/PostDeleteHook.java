package com.e2eq.framework.model.persistent.morphia;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Hook invoked after an entity has been deleted.
 */
@RegisterForReflection
public interface PostDeleteHook {
    void afterDelete(String realmId, Class<?> entityClass, String idAsString);
}
