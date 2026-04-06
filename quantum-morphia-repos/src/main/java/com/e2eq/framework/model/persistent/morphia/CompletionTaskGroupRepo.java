package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for {@link CompletionTaskGroup} entities.  Provides simple
 * subscription support so that interested parties can be notified when a task
 * in the group completes.
 */
@ApplicationScoped
public class CompletionTaskGroupRepo extends MorphiaRepo<CompletionTaskGroup> {

    private final Map<String, MultiEmitter<? super String>> emitters = new ConcurrentHashMap<>();

    public CompletionTaskGroup createGroup(CompletionTaskGroup group) {
        return createGroup(getSecurityContextRealmId(), group);
    }

    public CompletionTaskGroup createGroup(String realm, CompletionTaskGroup group) {
        group.setStatus(CompletionTaskGroup.Status.NEW);
        group.setCreatedDate(new java.util.Date());
        return save(realm, group);
    }

    public Multi<Object> subscribe(String groupId) {
        return Multi.createFrom().emitter(emitter -> emitters.put(groupId, emitter))
                .onTermination().invoke(() -> emitters.remove(groupId));
    }

    public void notifyGroup(String groupId, String message) {
        MultiEmitter<? super String> emitter = emitters.get(groupId);
        if (emitter != null) {
            emitter.emit(message);
        }
    }

    public Optional<CompletionTaskGroup> findByRefName(String realm, String refName) {
        return Optional.ofNullable(
                getMorphiaDataStoreWrapper().getDataStore(realm)
                        .find(CompletionTaskGroup.class)
                        .filter(dev.morphia.query.filters.Filters.eq("refName", refName))
                        .first()
        );
    }

    public Optional<CompletionTaskGroup> updateStatus(String realm, String groupId, CompletionTaskGroup.Status status) {
        Optional<CompletionTaskGroup> group = findById(new ObjectId(groupId), realm, true);
        if (group.isEmpty()) {
            return Optional.empty();
        }

        CompletionTaskGroup existing = group.get();
        existing.setStatus(status);
        if (status == CompletionTaskGroup.Status.COMPLETE) {
            existing.setCompletedDate(new java.util.Date());
        }
        return Optional.of(save(realm, existing));
    }
}
