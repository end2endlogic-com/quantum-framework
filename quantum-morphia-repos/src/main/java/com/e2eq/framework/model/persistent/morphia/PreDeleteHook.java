package com.e2eq.framework.model.persistent.morphia;

/**
 * Hook invoked before an entity is deleted. Implementations may throw to block deletion.
 */
public interface PreDeleteHook {
    void beforeDelete(String realmId, Object entity) throws RuntimeException;
}
