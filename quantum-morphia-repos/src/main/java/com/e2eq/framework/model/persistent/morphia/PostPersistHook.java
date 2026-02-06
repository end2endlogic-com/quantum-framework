package com.e2eq.framework.model.persistent.morphia;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * SPI hook invoked by MorphiaRepo after a successful save/merge of an entity.
 * Implementations can perform side effects such as indexing, cache updates, or ontology materialization.
 */
@RegisterForReflection
public interface PostPersistHook {
    /**
     * Called after an entity instance has been saved to the provided realm/datastore.
     *
     * @param realmId the realm/tenant id used for the save operation
     * @param entity  the entity instance that was just persisted
     */
    void afterPersist(String realmId, Object entity);
}
