package com.e2eq.framework.model.persistent.morphia;

/**
 * Hook invoked after an entity has been deleted.
 */
public interface PostDeleteHook {
    void afterDelete(String realmId, Class<?> entityClass, String idAsString);
}
