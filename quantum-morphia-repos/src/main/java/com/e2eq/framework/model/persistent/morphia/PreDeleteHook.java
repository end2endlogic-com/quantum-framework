package com.e2eq.framework.model.persistent.morphia;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Hook invoked before an entity is deleted. Implementations may throw to block deletion.
 */
@RegisterForReflection
public interface PreDeleteHook {
    void beforeDelete(String realmId, Object entity) throws RuntimeException;
}
