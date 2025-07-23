package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
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
        group.setStatus(CompletionTaskGroup.Status.NEW);
        group.setCreatedDate(new java.util.Date());
        return save(group);
    }

    public Multi<String> subscribe(String groupId) {
        return Multi.createFrom().emitter(emitter -> emitters.put(groupId, emitter))
                .onTermination().invoke(() -> emitters.remove(groupId));
    }

    public void notifyGroup(String groupId, String message) {
        MultiEmitter<? super String> emitter = emitters.get(groupId);
        if (emitter != null) {
            emitter.emit(message);
        }
    }
}
