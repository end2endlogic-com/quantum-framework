package com.e2eq.framework.model.persistent.morphia;

import io.quarkus.logging.Log;
import jakarta.enterprise.inject.Instance;

final class RepoLifecycleHooks {

    private final boolean autoMaterialize;
    private final Instance<PostPersistHook> postPersistHooks;
    private final Instance<PreDeleteHook> preDeleteHooks;
    private final Instance<PostDeleteHook> postDeleteHooks;

    RepoLifecycleHooks(
            boolean autoMaterialize,
            Instance<PostPersistHook> postPersistHooks,
            Instance<PreDeleteHook> preDeleteHooks,
            Instance<PostDeleteHook> postDeleteHooks) {
        this.autoMaterialize = autoMaterialize;
        this.postPersistHooks = postPersistHooks;
        this.preDeleteHooks = preDeleteHooks;
        this.postDeleteHooks = postDeleteHooks;
    }

    void callPostPersistHooks(String realmId, Object entity) {
        if (!autoMaterialize || postPersistHooks == null) {
            return;
        }
        for (PostPersistHook hook : postPersistHooks) {
            try {
                hook.afterPersist(realmId, entity);
            } catch (Throwable t) {
                Log.warnf(t, "PostPersistHook threw exception for realm=%s entity=%s",
                        realmId, entity != null ? entity.getClass().getName() : "<null>");
            }
        }
    }

    void callPreDeleteHooks(String realmId, Object entity) {
        if (preDeleteHooks == null) {
            return;
        }
        for (PreDeleteHook hook : preDeleteHooks) {
            try {
                hook.beforeDelete(realmId, entity);
            } catch (Throwable t) {
                throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
            }
        }
    }

    void callPostDeleteHooks(String realmId, Class<?> entityClass, String idAsString) {
        if (postDeleteHooks == null) {
            return;
        }
        for (PostDeleteHook hook : postDeleteHooks) {
            try {
                hook.afterDelete(realmId, entityClass, idAsString);
            } catch (Throwable t) {
                Log.warnf(t, "PostDeleteHook threw exception for realm=%s entityClass=%s id=%s",
                        realmId, entityClass != null ? entityClass.getName() : "<null>", idAsString);
            }
        }
    }
}
